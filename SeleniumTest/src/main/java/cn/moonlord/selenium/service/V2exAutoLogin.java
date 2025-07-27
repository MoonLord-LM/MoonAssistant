package cn.moonlord.tempfilestorage.service;

import cn.moonlord.tempfilestorage.utils.SeleniumUtil;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

@Slf4j
@Component
public class V2exAutoLogin {

    @SneakyThrows
    @PostConstruct
    public void run() {
        // 用户数据存储目录
        File userData = new File("www.v2ex.com");
        SeleniumUtil.prepareUserData(userData);

        // 打开签到页面
        ChromeDriver driver = SeleniumUtil.getInvisibleChrome(userData);
        driver.get("https://www.v2ex.com/mission/daily");
        SeleniumUtil.printPage(driver);

        // 检查并完成登录
        if (driver.getTitle() == null || driver.getTitle().contains("登录")) {
            log.info("当前未登录，进入登录流程");
            SeleniumUtil.cleanCacheAndQuit(driver);
            driver = SeleniumUtil.getVisibleChrome(userData);
            driver.get("https://www.v2ex.com/mission/daily");
            while (true) {
                try {
                    SeleniumUtil.printPage(driver);
                    if (driver.getCurrentUrl() != null && driver.getTitle() != null) {
                        if (driver.getCurrentUrl().startsWith("https://www.v2ex.com/") && !driver.getTitle().contains("登录")) {
                            break;
                        }
                    }
                    Thread.sleep(3000);
                } catch (NoSuchSessionException | NoSuchWindowException e) {
                    driver = SeleniumUtil.getVisibleChrome(userData);
                    driver.get("https://www.v2ex.com/mission/daily");
                }
            }
            SeleniumUtil.cleanCacheAndQuit(driver);
            driver = SeleniumUtil.getInvisibleChrome(userData);
            driver.get("https://www.v2ex.com/mission/daily");
            SeleniumUtil.printPage(driver);
        }

        // 执行签到
        log.info("当前已登录，进入签到流程");
        List<WebElement> collectButtons = driver.findElements(By.cssSelector("input[value*='领取'][value*='铜币']"));
        if (!collectButtons.isEmpty()) {
            log.info("点击领取铜币");
            collectButtons.get(0).click();
            Thread.sleep(3000);
            SeleniumUtil.printPage(driver);
        }
        List<WebElement> showButtons = driver.findElements(By.cssSelector("input[value*='查看'][value*='余额']"));
        if (!showButtons.isEmpty()) {
            log.info("点击查看余额");
            showButtons.get(0).click();
            Thread.sleep(3000);
            SeleniumUtil.printPage(driver);
        }
        List<WebElement> coinsButtons = driver.findElements(By.cssSelector("div.balance_area"));
        if (!coinsButtons.isEmpty()) {
            String value = String.valueOf(coinsButtons.get(0).getDomProperty("innerText")).replace("\n", ".");
            log.info("当前余额: " + value);
        }
        log.info("执行完成");
        SeleniumUtil.cleanCacheAndQuit(driver);

        // 清理多余文件
        SeleniumUtil.printCookie(userData);
        SeleniumUtil.cleanCookie(userData, List.of("v2ex.com"));
        SeleniumUtil.printLocalStorage(userData);
        SeleniumUtil.cleanLocalStorage(userData, List.of("v2ex.com"));
        SeleniumUtil.compactLocalStorage(userData);
        SeleniumUtil.cleanUserData(userData, true, true);
    }

}
