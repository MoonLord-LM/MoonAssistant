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

}
