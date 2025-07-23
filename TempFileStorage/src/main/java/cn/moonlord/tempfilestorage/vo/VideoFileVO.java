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
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

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
    
    /**
     * 获取格式化的视频时长（小时:分钟:秒）
     * @return 格式化的视频时长
     */
    public String getFormattedDuration() {
        if (super.getDuration() == null) {
            return null;
        }
        
        int totalSeconds = (int) Math.round(super.getDuration());
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
    
    /**
     * 获取格式化的视频帧率
     * @return 格式化的视频帧率
     */
    public String getFormattedFrameRate() {
        if (super.getFrameRate() == null) {
            return null;
        }
        return String.format("%.2f fps", super.getFrameRate());
    }
    
    /**
     * 获取格式化的视频比特率
     * @return 格式化的视频比特率
     */
    public String getFormattedVideoBitrate() {
        if (super.getVideoBitrate() == null) {
            return null;
        }
        
        if (super.getVideoBitrate() >= 1000) {
            return String.format("%.2f Mbps", super.getVideoBitrate() / 1000.0);
        } else {
            return String.format("%d kbps", super.getVideoBitrate());
        }
    }
    
    /**
     * 获取格式化的音频比特率
     * @return 格式化的音频比特率
     */
    public String getFormattedAudioBitrate() {
        if (super.getAudioBitrate() == null) {
            return null;
        }
        
        if (super.getAudioBitrate() >= 1000) {
            return String.format("%.2f Mbps", super.getAudioBitrate() / 1000.0);
        } else {
            return String.format("%d kbps", super.getAudioBitrate());
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

        // 提取视频元数据
        extractVideoMetadata(file);

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

    /**
     * 提取视频文件的元数据
     * @param file 视频文件
     */
    @SneakyThrows
    private void extractVideoMetadata(File file) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            
            // 获取视频分辨率
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width > 0 && height > 0) {
                super.setResolution(width + "x" + height);
            }
            
            // 获取视频帧率
            double frameRate = grabber.getVideoFrameRate();
            if (frameRate > 0) {
                super.setFrameRate(frameRate);
            }
            
            // 获取视频时长（微秒转换为秒）
            long duration = grabber.getLengthInTime();
            if (duration > 0) {
                super.setDuration(duration / 1000000.0);
            }
            
            // 获取视频编码格式
            String videoCodec = grabber.getVideoCodecName();
            if (videoCodec != null && !videoCodec.isEmpty()) {
                super.setVideoCodec(videoCodec);
            }
            
            // 获取音频编码格式
            String audioCodec = grabber.getAudioCodecName();
            if (audioCodec != null && !audioCodec.isEmpty()) {
                super.setAudioCodec(audioCodec);
            }
            
            // 获取视频比特率（bps 转换为 kbps）
            int videoBitrate = grabber.getVideoBitrate();
            if (videoBitrate > 0) {
                super.setVideoBitrate(videoBitrate / 1000);
            }
            
            // 获取音频比特率（bps 转换为 kbps）
            int audioBitrate = grabber.getAudioBitrate();
            if (audioBitrate > 0) {
                super.setAudioBitrate(audioBitrate / 1000);
            }
            
            grabber.stop();
        } catch (FrameGrabber.Exception e) {
            log.error("提取视频元数据失败: {}", e.getMessage(), e);
            // 出错时不抛出异常，只记录日志，避免影响正常流程
        }
    }
}
