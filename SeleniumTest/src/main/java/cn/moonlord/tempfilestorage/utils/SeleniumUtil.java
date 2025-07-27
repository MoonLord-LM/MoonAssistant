package cn.moonlord.tempfilestorage.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.v138.network.Network;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SeleniumUtil {

    public static ChromeDriver getVisibleChrome(File userData) throws IOException {
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
        return new ChromeDriver(options);
    }

    public static ChromeDriver getInvisibleChrome(File userData) throws IOException {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
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
        return new ChromeDriver(options);
    }

    public static void cleanCacheAndQuit(ChromeDriver driver) {
        driver.getDevTools().createSessionIfThereIsNotOne();
        driver.getDevTools().send(Network.clearBrowserCache());
        driver.close();
        driver.quit();
    }

    public static void printCookie(File userData) throws IOException, SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + userData.getCanonicalPath() + "/Default/Network/Cookies");
        Statement stmt = conn.createStatement();
        ResultSet result = stmt.executeQuery("select host_key, name, path, encrypted_value, expires_utc from cookies");
        log.info("Current Cookie");
        log.info("————————————————————————————————————————————————————————————");
        while (result.next()) {
            String host = result.getString("host_key");
            String path = result.getString("path");
            String name = result.getString("name");
            int encryptedValueSize = result.getBytes("encrypted_value").length;
            long expiresUtc = result.getLong("expires_utc");
            log.info("host: {},        path: {},        name: {},        encryptedValueLength: {},        expiresUtc: {}", host, path, name, encryptedValueSize, expiresUtc);
        }
        log.info("————————————————————————————————————————————————————————————");
        result.close();
        stmt.close();
        conn.close();
    }

    public static void cleanCookie(File userData, List<String> keepDomains) throws IOException, SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + userData.getCanonicalPath() + "/Default/Network/Cookies");
        String sql = "delete from cookies";
        if (keepDomains != null && !keepDomains.isEmpty()) {
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < keepDomains.size(); i++) {
                if (i > 0) where.append(" or ");
                where.append("host_key = ? or host_key like ?");
            }
            sql = sql + " where not (" + where + ")";
        }
        log.info("cleanCookie sql: {}", sql);
        PreparedStatement cleanStatement = conn.prepareStatement(sql);
        int index = 1;
        if (keepDomains != null && !keepDomains.isEmpty()) {
            for (String domain : keepDomains) {
                cleanStatement.setString(index++, domain);
                cleanStatement.setString(index++, "%" + domain);
            }
        }
        int result = cleanStatement.executeUpdate();
        log.info("cleanCookie result: {}", result);
        cleanStatement.close();
        Statement vacuumStatement = conn.createStatement();
        vacuumStatement.execute("vacuum");
        vacuumStatement.close();
        conn.close();
    }

    public static void printLocalStorage(File userData) throws IOException {
        File leveldbDir = new File(userData.getCanonicalPath() + "/Default/Local Storage/leveldb");
        if (!leveldbDir.exists() || !leveldbDir.isDirectory()) {
            log.error("printLocalStorage failed, userData path does not exist: {}", leveldbDir.getCanonicalPath());
            throw new IOException("printLocalStorage failed, path does not exist: " + leveldbDir.getCanonicalPath());
        }
        log.info("Current LocalStorage Files");
        log.info("————————————————————————————————————————————————————————————");
        File[] files = leveldbDir.listFiles();
        if (files != null) {
            for (File file : files) {
                log.info("file: {},        length: {}", file.getName(), file.length());
            }
        }
        log.info("————————————————————————————————————————————————————————————");
        File logFile = new File(leveldbDir, "LOG");
        if (logFile.exists()) {
            log.info("Current LocalStorage Log");
            log.info("————————————————————————————————————————————————————————————");
            List<String> lines = FileUtils.readLines(logFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                log.info("line: {}", line);
            }
            log.info("————————————————————————————————————————————————————————————");
        }
        Options options = new Options();
        options.createIfMissing(false);
        DB db = JniDBFactory.factory.open(leveldbDir, options);
        log.info("Current LocalStorage Value");
        log.info("————————————————————————————————————————————————————————————");
        DBIterator iterator = db.iterator();
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
            Map.Entry<byte[], byte[]> next = iterator.peekNext();
            String key = new String(next.getKey(), StandardCharsets.UTF_8);
            String value = new String(next.getValue(), StandardCharsets.UTF_8);
            if (key.contains("\u0000")) {
                key = key.replace("\u0000", "[\\u0000]");
            }
            if (key.contains("\u0001")) {
                key = key.replace("\u0001", "[\\u0001]");
            }
            if (value.contains("\u0001")) {
                value = value.replace("\u0001", "[\\u0001]");
            }
            if (value.contains("\uFFFD")) {
                value = "[0x" + Hex.toHexString(next.getValue()) + "]";
            }
            String keyHex = "[0x" + Hex.toHexString(next.getKey()) + "]";
            String valueHex = "[0x" + Hex.toHexString(next.getValue()) + "]";
            log.info("key: {},        value: {},        keyHex: {},        valueHex: {}", key, value, keyHex, valueHex);
        }
        iterator.close();
        db.close();
        log.info("————————————————————————————————————————————————————————————");
    }

    public static void cleanLocalStorage(File userData, List<String> keepDomains) throws IOException {
        File leveldbDir = new File(userData.getCanonicalPath() + "/Default/Local Storage/leveldb");
        if (!leveldbDir.exists() || !leveldbDir.isDirectory()) {
            log.error("cleanLocalStorage failed, userData path does not exist: {}", leveldbDir.getCanonicalPath());
            throw new IOException("cleanLocalStorage failed, path does not exist: " + leveldbDir.getCanonicalPath());
        }
        Options options = new Options();
        options.createIfMissing(false);
        DB db = JniDBFactory.factory.open(leveldbDir, options);
        DBIterator iterator = db.iterator();
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
            Map.Entry<byte[], byte[]> next = iterator.peekNext();
            String key = new String(next.getKey(), StandardCharsets.UTF_8);
            boolean shouldKeep = key.equals("VERSION");
            String domains = key;
            if (domains.startsWith("META:")) {
                domains = domains.substring("META:".length());
            } else if (domains.startsWith("METAACCESS:")) {
                domains = domains.substring("METAACCESS:".length());
            } else if (domains.startsWith("_")) {
                domains = domains.substring("_".length());
            }
            if (domains.contains("\u0000\u0001")) {
                domains = domains.substring(0, domains.indexOf("\u0000\u0001"));
            }
            String[] lsDomains = domains.split("/\\^0");
            for (String lsDomain : lsDomains) {
                if (!shouldKeep && keepDomains != null && !keepDomains.isEmpty()) {
                    for (String keepDomain : keepDomains) {
                        if (lsDomain.endsWith(keepDomain)) {
                            shouldKeep = true;
                            break;
                        }
                    }
                }
            }
            if (!shouldKeep) {
                db.delete(next.getKey());
                log.info("cleanLocalStorage deleted key: {},        value length: {}", key, next.getValue().length);
            }
        }
        iterator.close();
        db.compactRange(null, null);
        db.close();
    }

    public static void resetLocalStorage(File userData) throws IOException {
        File leveldbDir = new File(userData.getCanonicalPath() + "/Default/Local Storage/leveldb");
        if (!leveldbDir.exists() || !leveldbDir.isDirectory()) {
            log.error("resetLocalStorage failed, userData path does not exist: {}", leveldbDir.getCanonicalPath());
            throw new IOException("cleanLocalStorage failed, path does not exist: " + leveldbDir.getCanonicalPath());
        }
        Options options = new Options();
        options.createIfMissing(true);
        DB db = JniDBFactory.factory.open(leveldbDir, options);
        DBIterator iterator = db.iterator();
        Map<byte[], byte[]> data = new HashMap<>();
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
            Map.Entry<byte[], byte[]> next = iterator.peekNext();
            data.put(next.getKey(), next.getValue());
        }
        iterator.close();
        db.close();
        FileUtils.deleteQuietly(leveldbDir);
        Boolean ignored = leveldbDir.mkdir();
        options = new Options();
        options.createIfMissing(true);
        db = JniDBFactory.factory.open(leveldbDir, options);
        for (Map.Entry<byte[], byte[]> entry : data.entrySet()) {
            db.put(entry.getKey(), entry.getValue());
        }
        db.compactRange(null, null);
        db.close();
        FileUtils.deleteQuietly(new File(leveldbDir.getCanonicalPath() + "/LOCK"));
        FileUtils.deleteQuietly(new File(leveldbDir.getCanonicalPath() + "/LOG"));
        FileUtils.deleteQuietly(new File(leveldbDir.getCanonicalPath() + "/LOG.old"));
    }

    public static void prepareUserData(File userData) throws IOException {
        if (!userData.exists() || !userData.isDirectory()) {
            log.info("Create userData directory: {}", userData.mkdirs());
        }
        if (!userData.canRead() || !userData.canWrite()) {
            log.error("Can not read or write: {}", userData.getCanonicalPath());
            throw new IOException("Can not read or write: " + userData.getCanonicalPath());
        }
    }

    public static void cleanUserData(File userData, Boolean keepCookie, Boolean keepLocalStorage) throws IOException {
        List<String> keepPaths = new ArrayList<>();
        if (keepCookie) {
            keepPaths.add(userData.getCanonicalPath() + "\\Default\\Network\\Cookies");
        }
        if (keepLocalStorage) {
            keepPaths.add(userData.getCanonicalPath() + "\\Default\\Local Storage");
        }
        if (keepCookie || keepLocalStorage) {
            keepPaths.add(userData.getCanonicalPath() + "\\Local State");
        }
        List<File> allFiles;
        try (Stream<Path> stream = Files.walk(userData.toPath())) {
            allFiles = stream.map(Path::toFile).collect(Collectors.toList());
        }
        Collections.reverse(allFiles);
        for (File file : allFiles) {
            if (!file.getCanonicalPath().equals(userData.getCanonicalPath())) {
                boolean keep = false;
                for (String keepPath : keepPaths) {
                    if (file.getCanonicalPath().equals(keepPath) || file.getCanonicalPath().startsWith(keepPath) || keepPath.startsWith(file.getCanonicalPath())) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    FileUtils.deleteQuietly(file);
                }
            }
        }
    }

}
