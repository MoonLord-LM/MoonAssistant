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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
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
        DosFileAttributes attrs = Files.readAttributes(file.toPath(), DosFileAttributes.class);
        super.setFileCreationTime(attrs.creationTime().toMillis());
        super.setFileLastAccessTime(attrs.lastAccessTime().toMillis());
        super.setFileLastUpdateTime(attrs.lastModifiedTime().toMillis());

        List<FileHash> fileHashes = new ArrayList<>();
        // TODO
        // fileHashes.add(new FileHash("MD5", calculateMD5(file)));
        // fileHashes.add(new FileHash("SHA-256", calculateSHA256(file)));

        super.setFileHashes(fileHashes);
    }

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
