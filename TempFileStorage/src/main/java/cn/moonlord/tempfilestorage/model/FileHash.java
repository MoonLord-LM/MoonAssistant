package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件哈希信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
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
