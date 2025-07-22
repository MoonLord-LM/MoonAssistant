package cn.moonlord.tempfilestorage.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 视频文件信息
 */
@Slf4j
@Data
public class VideoFile {

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
     * 创建时间
     */
    Long creationTime = System.currentTimeMillis();

    /**
     * 最后更新时间
     */
    Long lastUpdateTime = System.currentTimeMillis();

}
