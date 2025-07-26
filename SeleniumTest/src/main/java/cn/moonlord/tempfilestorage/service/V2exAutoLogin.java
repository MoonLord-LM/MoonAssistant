package cn.moonlord.tempfilestorage.service;

import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.v138.network.Network;
import org.springframework.stereotype.Component;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class V2exAutoLogin {

    @SneakyThrows
    @PostConstruct
    public void run() {
        try {
            // 用户数据存储目录
            File userData = new File("www.v2ex.com");
            if (!userData.exists() || !userData.isDirectory()) {
                outputLog("创建用户目录: " + userData.mkdir());
            }
            outputLog("当前用户目录为: " + userData.getCanonicalPath() + ", 可读: " + userData.canRead() + ", 可写: " + userData.canWrite());

            // 关闭已运行的 Chrome 程序
            try {
                Runtime.getRuntime().exec("taskkill.exe /f /im chromedriver.exe");
            } catch (java.io.IOException e) {
                outputLog("关闭Chrome进程失败: " + e.getMessage());
            }
            Thread.sleep(3000);

            // 创建实例
            ChromeDriver driver = new ChromeDriver(getInvisibleChromeOptions(userData));
            driver.get("https://www.v2ex.com/mission/daily");
            outputLog("当前页面: " + driver.getCurrentUrl() + " 标题: " + driver.getTitle() + " 内容: " + Jsoup.parse(driver.getPageSource()).text());

            // 检查并手动完成登录
            if (driver.getTitle().contains("登录")) {
                outputLog("当前未登录，进入登录流程");
                cleanAndQuit(driver);
                driver = new ChromeDriver(getVisibleChromeOptions(userData));
                driver.get("https://www.v2ex.com/mission/daily");
                while (true) {
                    try {
                        if (driver.getCurrentUrl().contains("https://www.v2ex.com/") && !driver.getTitle().contains("登录")) {
                            break;
                        }
                        outputLog("当前未登录: " + driver.getCurrentUrl() + " 标题: " + driver.getTitle() + " 内容: " + Jsoup.parse(driver.getPageSource()).text());
                        Thread.sleep(3000);
                    } catch (NoSuchSessionException | NoSuchWindowException e) {
                        outputLog("登录页面被关闭，重新打开");
                        driver = new ChromeDriver(getVisibleChromeOptions(userData));
                        driver.get("https://www.v2ex.com/mission/daily");
                    }
                }
                outputLog("登录流程完成");
                cleanAndQuit(driver);
                driver = new ChromeDriver(getInvisibleChromeOptions(userData));
                driver.get("https://www.v2ex.com/mission/daily");
                outputLog("当前页面: " + driver.getCurrentUrl() + " 标题: " + driver.getTitle() + " 内容: " + Jsoup.parse(driver.getPageSource()).text());
            }

            // 执行签到
            outputLog("当前已登录，进入签到流程");
            List<WebElement> collectButtons = driver.findElements(By.cssSelector("input[value*='领取'][value*='铜币']"));
            if (!collectButtons.isEmpty()) {
                outputLog("点击领取铜币");
                collectButtons.get(0).click();
                Thread.sleep(3000);
            }
            List<WebElement> showButtons = driver.findElements(By.cssSelector("input[value*='查看'][value*='余额']"));
            if (!showButtons.isEmpty()) {
                outputLog("点击查看余额");
                showButtons.get(0).click();
                Thread.sleep(3000);
            }
            outputLog("当前页面: " + driver.getCurrentUrl() + " 标题: " + driver.getTitle() + " 内容: " + Jsoup.parse(driver.getPageSource()).text());
            List<WebElement> coinsButtons = driver.findElements(By.cssSelector("div.balance_area"));
            if (!coinsButtons.isEmpty()) {
                String value = String.valueOf(coinsButtons.get(0).getAttribute("innerText")).replace("\n", ".");
                outputLog("当前余额: " + value);
            }

            // 结束和清理
            outputLog("执行完成");
            cleanAndQuit(driver);
            cleanTempFile(userData);
        } catch (Exception e) {
            System.err.println("Exception: " + e.getMessage());
            System.out.flush();
        }
    }

    @SneakyThrows
    public static void outputLog(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    @SneakyThrows
    public static ChromeOptions getVisibleChromeOptions(File userData) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--no-restore-session-state");
        options.addArguments("--disable-cache");
        options.addArguments("--disable-application-cache");
        options.addArguments("--disk-cache-size=0");
        options.addArguments("--profile-directory=Default");
        options.addArguments("--remote-debugging-port=9222");
        options.addArguments("--window-size=1280,720");
        options.addArguments("--user-data-dir=" + userData.getCanonicalPath());
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        return options;
    }

    @SneakyThrows
    public static ChromeOptions getInvisibleChromeOptions(File userData) {
        ChromeOptions options = getVisibleChromeOptions(userData);
        options.addArguments("--headless=new");
        return options;
    }

    @SneakyThrows
    public static void cleanAndQuit(ChromeDriver driver) {
        driver.getDevTools().createSessionIfThereIsNotOne();
        driver.getDevTools().send(Network.clearBrowserCache());
        driver.get("https://accounts.google.com");
        driver.manage().deleteAllCookies();
        driver.executeScript("window.localStorage.clear();");
        driver.executeScript("window.sessionStorage.clear();");
        driver.get("https://www.google.com");
        driver.manage().deleteAllCookies();
        driver.executeScript("window.localStorage.clear();");
        driver.executeScript("window.sessionStorage.clear();");
        driver.get("https://www.youtube.com");
        driver.manage().deleteAllCookies();
        driver.executeScript("window.localStorage.clear();");
        driver.executeScript("window.sessionStorage.clear();");
        Thread.sleep(3000);
        driver.close();
        driver.quit();
    }

    @SneakyThrows
    public static void cleanTempFile(File userData) {
        // 读取并打印 Local Storage 数据
        readLocalStorageData(userData);

        Runtime.getRuntime().exec("powershell.exe -Command \"Get-ChildItem -Path \\\"\"" + userData.getCanonicalPath() + "\\\"\" -Recurse -File | Where-Object { $_.Length -eq 0 } | Remove-Item\"");
        Thread.sleep(3000);
        Runtime.getRuntime().exec("powershell.exe -Command \"Get-ChildItem -Path \\\"\"" + userData.getCanonicalPath() + "\\\"\" -Recurse -Directory | Where-Object { $_.GetFiles().Count -eq 0 -and $_.GetDirectories().Count -eq 0 } | Remove-Item\"");
        Thread.sleep(3000);
        Runtime.getRuntime().exec("powershell.exe -Command \"Get-ChildItem -Path \\\"\"" + userData.getCanonicalPath() + "\\\"\" -Recurse -Directory | Where-Object { $_.GetFiles().Count -eq 0 -and $_.GetDirectories().Count -eq 0 } | Remove-Item\"");
        Thread.sleep(3000);
        List<String> cleanFiles = List.of(
            "Breadcrumbs", "BrowserMetrics-spare.pma", "chrome_debug.log", "CrashpadMetrics-active.pma", "DevToolsActivePort", "first_party_sets.db",
            "Variations", "Last Browser", "Last Version",
            "Default/History", "Default/Web Data", "Default/Account Web Data", "Default/Favicons", "Default/Network Action Predictor", "Default/Affiliation Database",
            "Default/Login Data For Account", "Default/Login Data", "Default/DIPS", "Default/BrowsingTopicsSiteData", "Default/MediaDeviceSalts",
            "Default/Preferences", "Default/Top Sites", "Default/Shortcuts", "Default/ServerCertificate", "Default/heavy_ad_intervention_opt_out.db",
            "Default/Google Profile Picture.png", "Default/SharedStorage", "Default/BookmarkMergedSurfaceOrdering",
            "Default/BrowsingTopicsState", "Default/passkey_enclave_state",
            "Default/PreferredApps", "Default/README", "Default/Secure Preferences", "Default/trusted_vault.pb",
            "Default/Network/Network Persistent State", "Default/Network/Reporting and NEL", "Default/Network/Trust Tokens",
            "Default/Network/SCT Auditing Pending Reports", "Default/Network/TransportSecurity"
        );
        for (String file : cleanFiles) {
            FileUtils.deleteQuietly(new File(userData.getCanonicalPath() + "/" + file));
        }
        List<String> cleanDirs = List.of(
            "component_crx_cache", "Crashpad", "extensions_crx_cache", "GraphiteDawnCache", "GrShaderCache", "BrowserMetrics",
            "OriginTrials", "optimization_guide_model_store", "segmentation_platform", "ShaderCache",
            "Default/Accounts", "Default/AutofillStrikeDatabase", "Default/BudgetDatabase",
            "Default/Cache", "Default/chrome_cart_db", "Default/ClientCertificates", "Default/Code Cache", "Default/commerce_subscription_db",
            "Default/DawnGraphiteCache", "Default/DawnWebGPUCache", "Default/Download Service", "Default/discounts_db",
            "Default/Extension Rules", "Default/Extension State", "Default/Extension Scripts", "Default/Feature Engagement Tracker",
            "Default/GCM Store", "Default/GPUCache", "Default/IndexedDB", "Default/Service Worker",
            "Default/optimization_guide_model_store", "Default/optimization_guide_hint_cache_store",
            "Default/parcel_tracking_db", "Default/PersistentOriginTrials", "Default/Safe Browsing Network", "Default/Sync Data",
            "Default/Segmentation Platform", "Default/Session Storage", "Default/Sessions",
            "Default/Shared Dictionary", "Default/shared_proto_db",
            "Default/Site Characteristics Database",
            "Default/WebStorage"
        );
        for (String dir : cleanDirs) {
            FileUtils.deleteDirectory(new File(userData.getCanonicalPath() + "/" + dir));
        }
    }

    /**
     * 读取 Chrome Local Storage 中的 LevelDB 数据
     * @param userData Chrome 用户数据目录
     */
    @SneakyThrows
    public static void readLocalStorageData(File userData) {
        File leveldbDir = new File(userData.getCanonicalPath() + "/Default/Local Storage/leveldb");
        outputLog("开始读取 Local Storage 数据，路径: " + leveldbDir.getCanonicalPath());

        if (!leveldbDir.exists() || !leveldbDir.isDirectory()) {
            outputLog("Local Storage 目录不存在: " + leveldbDir.getCanonicalPath());
            return;
        }

        // 首先尝试直接读取文件内容
        outputLog("===== Local Storage 文件列表 =====");
        File[] files = leveldbDir.listFiles();
        if (files != null) {
            for (File file : files) {
                outputLog("文件: " + file.getName() + ", 大小: " + file.length() + " 字节");
            }
        }

        // 尝试读取 MANIFEST 文件和 LOG 文件，这些通常是文本格式
        try {
            File manifestFile = new File(leveldbDir, "MANIFEST-000001");
            if (manifestFile.exists()) {
                outputLog("===== MANIFEST 文件内容 =====");
                List<String> lines = FileUtils.readLines(manifestFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    outputLog(line);
                }
            }

            File logFile = new File(leveldbDir, "LOG");
            if (logFile.exists()) {
                outputLog("===== LOG 文件内容 =====");
                List<String> lines = FileUtils.readLines(logFile, StandardCharsets.UTF_8);
                for (int i = 0; i < Math.min(lines.size(), 20); i++) { // 只显示前20行
                    outputLog(lines.get(i));
                }
            }
        } catch (Exception e) {
            outputLog("读取文本文件时出错: " + e.getMessage());
        }

        // 尝试使用 LevelDB API 读取数据库
        try {
            outputLog("===== 尝试使用 LevelDB API 读取数据 =====");
            Options options = new Options();
            options.createIfMissing(false);
            // 添加更多选项以提高兼容性
            options.verifyChecksums(false);
            options.paranoidChecks(false);

            DB db = factory.open(leveldbDir, options);
            outputLog("成功打开 LevelDB 数据库");

            try (DBIterator iterator = db.iterator()) {
                for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                    try {
                        byte[] key = iterator.peekNext().getKey();
                        byte[] value = iterator.peekNext().getValue();

                        // 尝试将键值对转换为字符串
                        String keyStr = new String(key, StandardCharsets.UTF_8);

                        // 对于值，先尝试 UTF-8 解码，如果失败则显示十六进制
                        String valueStr;
                        try {
                            valueStr = new String(value, StandardCharsets.UTF_8);
                            // 检查是否为有效的 UTF-8 字符串
                            if (!isValidUTF8(valueStr)) {
                                valueStr = bytesToHex(value);
                            }
                        } catch (Exception e) {
                            valueStr = bytesToHex(value);
                        }

                        outputLog("键: " + keyStr + ", 值: " + valueStr);
                    } catch (Exception e) {
                        outputLog("读取键值对时出错: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                outputLog("遍历 LevelDB 数据时出错: " + e.getMessage());
            } finally {
                db.close();
            }
        } catch (Exception e) {
            outputLog("打开 LevelDB 数据库失败: " + e.getMessage());

            // 如果 LevelDB API 失败，尝试直接读取 .ldb 文件的二进制内容
            outputLog("===== 尝试直接读取 .ldb 文件 =====");
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".ldb")) {
                        try {
                            byte[] fileContent = FileUtils.readFileToByteArray(file);
                            outputLog("文件 " + file.getName() + " 的十六进制内容 (前100字节): " +
                                     bytesToHex(fileContent, 0, Math.min(fileContent.length, 100)));
                        } catch (Exception ex) {
                            outputLog("读取文件 " + file.getName() + " 失败: " + ex.getMessage());
                        }
                    }
                }
            }
        }

        outputLog("===== Local Storage 数据读取结束 =====");
    }

    /**
     * 检查字符串是否为有效的 UTF-8 编码
     */
    private static boolean isValidUTF8(String str) {
        return str != null && !str.contains("\uFFFD");
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length);
    }

    /**
     * 将字节数组的指定部分转换为十六进制字符串
     */
    private static String bytesToHex(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

}
