package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 视频文件信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class VideoFile extends BaseData {

    /**
     * 序列号
     */
    String serialNumber;

    /**
     * 演员列表
     */
    List<String> actorNames;

    /**
     * 分类标签
     */
    List<String> marks;

    /**
     * 评分
     */
    Integer score;

    /**
     * 文件名称
     */
    String fileName;

    /**
     * 文件大小（字节）
     */
    Long fileSize;

    /**
     * 文件大小说明（2.00 GB）
     */
    String fileSizeString;

    /**
     * 文件创建时间
     */
    Long fileCreationTime;

    /**
     * 文件创建时间
     */
    String fileCreationTimeString;

    /**
     * 文件最后访问时间
     */
    Long fileLastAccessTime;

    /**
     * 文件最后访问时间
     */
    String fileLastAccessString;

    /**
     * 文件最后更新时间
     */
    Long fileLastUpdateTime;

    /**
     * 文件最后更新时间
     */
    String fileLastUpdateString;

    /**
     * 文件哈希值列表
     */
    List<FileHash> fileHashes;

    /**
     * 视频宽度
     */
    Integer width;

    /**
     * 视频高度
     */
    Integer height;

    /**
     * 分辨率（1920 x 1080）
     */
    String resolution;

    /**
     * 视频帧率
     */
    Double frameRate;

    /**
     * 视频帧率（59.94 fps、29.97 fps）
     */
    String frameRateString;

    /**
     * 视频时长（秒）
     */
    Double duration;

    /**
     * 视频时长（xx:xx:xx）
     */
    String durationString;

    /**
     * 视频编码格式
     */
    String videoCodec;

    /**
     * 音频编码格式
     */
    String audioCodec;

    /**
     * 视频比特率
     */
    Integer videoBitrate;

    /**
     * 视频比特率（kbps）
     */
    String videoBitrateString;

    /**
     * 音频比特率
     */
    Integer audioBitrate;

    /**
     * 音频比特率（kbps）
     */
    String audioBitrateString;

    public void setFileSize(final Long fileSize) {
        this.fileSize = fileSize;
        this.fileSizeString = formatFileSize(fileSize);
    }

    public void setFileCreationTime(final Long fileCreationTime) {
        this.fileCreationTime = fileCreationTime;
        this.fileCreationTimeString = formatTimestamp(fileCreationTime);
    }

    public void setFileLastAccessTime(final Long fileLastAccessTime) {
        this.fileLastAccessTime = fileLastAccessTime;
        this.fileLastAccessString = formatTimestamp(fileLastAccessTime);
    }

    public void setFileLastUpdateTime(final Long fileLastUpdateTime) {
        this.fileLastUpdateTime = fileLastUpdateTime;
        this.fileLastUpdateString = formatTimestamp(fileLastUpdateTime);
    }

    public void setFrameRate(final Double frameRate) {
        this.frameRate = frameRate;
        if (frameRate != null) {
            this.frameRateString = String.format("%.2f fps", frameRate);
        } else {
            this.frameRateString = null;
        }
    }

    public void setDuration(final Double duration) {
        this.duration = duration;
        if (duration != null) {
            int totalSeconds = (int) Math.round(duration);
            int hours = totalSeconds / 3600;
            int minutes = (totalSeconds % 3600) / 60;
            int seconds = totalSeconds % 60;
            this.durationString = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            this.durationString = null;
        }
    }

    public void setVideoBitrate(final Integer videoBitrate) {
        this.videoBitrate = videoBitrate;
        this.videoBitrateString = formatBitrate(videoBitrate);
    }

    public void setAudioBitrate(final Integer audioBitrate) {
        this.audioBitrate = audioBitrate;
        this.audioBitrateString = formatBitrate(audioBitrate);
    }

    public static String formatFileSize(final Long fileSize) {
        if (fileSize == null || fileSize < 0) {
            return null;
        }
        double size = fileSize;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB", "RB", "QB"};
        int unitIndex = 0;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s", size, units[unitIndex]);
    }

    public static String formatTimestamp(final Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).format(new Date(timestamp));
    }

    public static String formatBitrate(final Integer bitrate) {
        if (bitrate == null || bitrate < 0) {
            return null;
        }
        double rate = bitrate;
        String[] units = {"bps", "kbps", "Mbps", "Gbps", "Tbps", "Pbps", "Ebps", "Zbps", "Ybps", "Rbps", "Qbps"};
        int unitIndex = 0;
        while (rate >= 1000 && unitIndex < units.length - 1) {
            rate /= 1000;
            unitIndex++;
        }
        return String.format("%.2f %s", rate, units[unitIndex]);
    }

}
