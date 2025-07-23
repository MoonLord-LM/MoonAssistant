package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 磁盘逻辑卷信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class LogicalVolume extends BaseData {

    /**
     * 物理设备
     */
    DiskHardware hardware;

    /**
     * 物理磁盘在当前系统内的 Index
     */
    Integer hardwareIndex;

    /**
     * 序列号
     */
    String serialNumber;

    /**
     * 盘符 ID（C:、D:）
     */
    String deviceID;

    /**
     * 盘符名称（C:、D:）
     */
    String caption;

    /**
     * 用户自定义名称（系统、文件）
     */
    String name;

    /**
     * 文件系统（NTFS）
     */
    String fileSystem;

    /**
     * 文件路径长度限制（255）
     */
    Long maximumComponentLength;

    /**
     * 总容量（字节数）
     */
    Long totalSpace;

    /**
     * 总容量说明（3.64 TB、16.37 TB）
     */
    String totalSpaceString;

    /**
     * 可用容量（字节数）
     */
    Long freeSpace;

    /**
     * 总容量说明（3.64 TB、16.37 TB）
     */
    String freeSpaceString;

}
