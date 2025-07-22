package cn.moonlord.tempfilestorage.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件哈希信息
 */
@Slf4j
@Data
public class FileHash {

    /**
     * 哈希算法
     */
    String hashAlgorithm;

    /**
     * 文件哈希值
     */
    String hashValue;

}
