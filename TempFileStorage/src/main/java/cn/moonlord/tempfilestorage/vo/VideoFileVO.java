package cn.moonlord.tempfilestorage.vo;

import cn.moonlord.tempfilestorage.model.FileHash;
import cn.moonlord.tempfilestorage.model.VideoFile;
import cn.moonlord.tempfilestorage.utils.Ed2kUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频文件信息转换类
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class VideoFileVO extends VideoFile {

    /**
     * 文件
     */
    @JsonIgnore
    File file;

    @SneakyThrows
    public VideoFileVO(final File file, final Boolean addVideoInfo, final Boolean addHashInfo) {
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

        // 修改不符合要求的文件名
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

        this.setFile(file);
        if (addVideoInfo) addVideoInfo(file);
        if (addHashInfo) addHashInfo(file);
    }

    @SneakyThrows
    public void setFile(final File file) {
        this.file = file;
        this.setFileName(file.getName());
        this.setFileSize(file.length());
        DosFileAttributes attrs = Files.readAttributes(file.toPath(), DosFileAttributes.class);
        this.setFileCreationTime(attrs.creationTime().toMillis());
        this.setFileCreationTime(attrs.creationTime().toMillis());
        this.setFileLastAccessTime(attrs.lastAccessTime().toMillis());
        this.setFileLastUpdateTime(attrs.lastModifiedTime().toMillis());
    }

    @SneakyThrows
    public void addVideoInfo(File file) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width > 0 && height > 0) {
                super.setWidth(width);
                super.setHeight(height);
                super.setResolution(width + " x " + height);
            }
            double frameRate = grabber.getVideoFrameRate();
            if (frameRate > 0) {
                super.setFrameRate(frameRate);
            }
            long duration = grabber.getLengthInTime();
            if (duration > 0) {
                super.setDuration((double) (duration / 1000000));
            }
            String videoCodec = grabber.getVideoCodecName();
            if (videoCodec != null && !videoCodec.isEmpty()) {
                super.setVideoCodec(videoCodec);
            }
            String audioCodec = grabber.getAudioCodecName();
            if (audioCodec != null && !audioCodec.isEmpty()) {
                super.setAudioCodec(audioCodec);
            }
            int videoBitrate = grabber.getVideoBitrate();
            if (videoBitrate > 0) {
                super.setVideoBitrate(videoBitrate);
            }
            int audioBitrate = grabber.getAudioBitrate();
            if (audioBitrate > 0) {
                super.setAudioBitrate(audioBitrate);
            }
            grabber.stop();
        }
    }

    @SneakyThrows
    public void addHashInfo(File file) {
        List<FileHash> fileHashes = new ArrayList<>();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        fileHashes.add(new FileHash("ED2K", Ed2kUtil.generateLink(fileBytes, file.getName())));
        fileHashes.add(new FileHash("MD5", calculateHash(fileBytes, "MD5")));
        fileHashes.add(new FileHash("SHA-1", calculateHash(fileBytes, "SHA-1")));
        fileHashes.add(new FileHash("SHA-256", calculateHash(fileBytes, "SHA-256")));
        fileHashes.add(new FileHash("SHA-512", calculateHash(fileBytes, "SHA-512")));
        super.setFileHashes(fileHashes);
    }

    private String calculateHash(byte[] data, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] result = digest.digest(data);
        return Hex.toHexString(result);
    }

}
