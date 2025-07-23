package cn.moonlord.tempfilestorage.vo;

import cn.moonlord.tempfilestorage.model.FileHash;
import cn.moonlord.tempfilestorage.model.VideoFile;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.io.DigestInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频文件信息转换类
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class VideoFileVO extends VideoFile {

    @Override
    public void setFileSize(final Long fileSize) {
        super.setFileSize(fileSize);
        if (fileSize != null) {
            double size = fileSize;
            String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB", "RB", "QB"};
            int unitIndex = 0;
            while (size >= 1024 && unitIndex < units.length - 1) {
                size /= 1024;
                unitIndex++;
            }
            super.setFileSizeString(String.format("%.2f %s", size, units[unitIndex]));
        }
    }

    @SneakyThrows
    public VideoFileVO(final File file) {
        // 连续两个空格
        String tmp = file.getName();
        while (tmp.contains("  ")) {
            tmp = tmp.replaceAll("  ", " ");
        }
        // + 号前面没写空格
        String[] fileNames = tmp.split(" ");
        if (fileNames.length == 3) {
            if (tmp.contains("+") && !tmp.contains(" +")) {
                tmp = tmp.replace("+", " +");
                fileNames = tmp.split(" ");
            }
        }
        // 第一段后面没有空格
        if (fileNames.length == 3) {
            String beginPart = fileNames[0];
            for (int i = 0; i < beginPart.length(); i++) {
                if (String.valueOf(beginPart.charAt(i)).matches("[A-Za-z0-9-]")) {
                    beginPart = beginPart.substring(0, i) + " " + beginPart.substring(i);
                    tmp = beginPart + " " + fileNames[1] + " " + fileNames[2];
                    fileNames = tmp.split(" ");
                    break;
                }
            }
        }

        if (!tmp.equals(file.getName())) {
            FileUtils.moveFile(file, new File(file.getParent() + "\\" + tmp));
        }

        if (fileNames.length == 4) {
            super.setSerialNumber(fileNames[0]);
            super.setActorNames(List.of(fileNames[1].split("&")));
            super.setMarks(List.of(fileNames[2].split("&")));
            String endPart = fileNames[3];
            if (endPart.startsWith("+")) {
                endPart = endPart.substring(1);
            } else {
                throw new IllegalArgumentException("invalid file name: " + tmp);
            }
            if (endPart.contains(".")) {
                endPart = endPart.substring(0, endPart.indexOf("."));
            } else {
                throw new IllegalArgumentException("invalid file name: " + tmp);
            }
            super.setScore(Integer.valueOf(endPart));
        } else {
            throw new IllegalArgumentException("invalid file name: " + tmp);
        }

        super.setFileName(file.getName());
        this.setFileSize(file.length());
        
        try {
            // 获取文件的基本属性，包括创建时间和最后访问时间
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            super.setFileCreationTime(attrs.creationTime().toMillis());
            super.setFileLastAccessTime(attrs.lastAccessTime().toMillis());
            super.setFileLastUpdateTime(attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            log.warn("Failed to read file attributes: {}", e.getMessage());
            // 如果获取失败，则使用lastModified作为备选
            super.setFileCreationTime(file.lastModified());
            super.setFileLastAccessTime(file.lastModified());
            super.setFileLastUpdateTime(file.lastModified());
        }

        List<FileHash> fileHashes = new ArrayList<>();
        try {
            // 计算MD5哈希值
            FileHash md5Hash = new FileHash();
            md5Hash.setHashAlgorithm("MD5");
            md5Hash.setHashValue(calculateMD5(file));
            fileHashes.add(md5Hash);
            
            // 计算SHA-256哈希值
            FileHash sha256Hash = new FileHash();
            sha256Hash.setHashAlgorithm("SHA-256");
            sha256Hash.setHashValue(calculateSHA256(file));
            fileHashes.add(sha256Hash);
        } catch (IOException e) {
            log.warn("Failed to calculate file hash: {}", e.getMessage());
        }
        super.setFileHashes(fileHashes);
    }

    /**
     * 计算文件的MD5哈希值
     * 
     * @param file 要计算哈希值的文件
     * @return MD5哈希值的十六进制字符串
     * @throws IOException 如果文件读取失败
     */
    private String calculateMD5(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            MD5Digest digest = new MD5Digest();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] result = new byte[digest.getDigestSize()];
            digest.doFinal(result, 0);
            return new String(Hex.encode(result));
        }
    }

    /**
     * 计算文件的SHA-256哈希值
     * 
     * @param file 要计算哈希值的文件
     * @return SHA-256哈希值的十六进制字符串
     * @throws IOException 如果文件读取失败
     */
    private String calculateSHA256(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            SHA256Digest digest = new SHA256Digest();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] result = new byte[digest.getDigestSize()];
            digest.doFinal(result, 0);
            return new String(Hex.encode(result));
        }
    }
}
