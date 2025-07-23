package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    List<String> ActorNames;

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
    private String fileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件大小说明（2.00 GB）
     */
    String fileSizeString;

    /**
     * 文件创建时间
     */
    private Long fileCreationTime;

    /**
     * 文件最后访问时间
     */
    private Long fileLastAccessTime;

    /**
     * 文件最后更新时间
     */
    private Long fileLastUpdateTime;

    /**
     * 文件哈希值列表
     */
    List<FileHash> fileHashes;
    
    /**
     * 视频分辨率（如 1920x1080）
     */
    private String resolution;
    
    /**
     * 视频帧率（如 30 fps）
     */
    private Double frameRate;
    
    /**
     * 视频时长（秒）
     */
    private Double duration;
    
    /**
     * 视频编码格式（如 H.264, HEVC 等）
     */
    private String videoCodec;
    
    /**
     * 音频编码格式（如 AAC, MP3 等）
     */
    private String audioCodec;
    
    /**
     * 视频比特率（kbps）
     */
    private Integer videoBitrate;
    
    /**
     * 音频比特率（kbps）
     */
    private Integer audioBitrate;

}
