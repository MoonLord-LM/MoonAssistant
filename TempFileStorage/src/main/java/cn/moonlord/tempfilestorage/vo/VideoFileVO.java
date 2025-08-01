package cn.moonlord.tempfilestorage.vo;

import cn.moonlord.tempfilestorage.model.FileHash;
import cn.moonlord.tempfilestorage.model.VideoFile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributes;
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
