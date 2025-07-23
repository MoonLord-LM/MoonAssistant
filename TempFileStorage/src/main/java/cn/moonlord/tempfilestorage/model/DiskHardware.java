package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 磁盘物理设备信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DiskHardware {

    /**
     * 型号（Seagate XXX SCSI Disk Device、WD XXX USB Device）
     */
    String model;

    /**
     * 名称（Seagate XXX SCSI Disk Device、WD XXX USB Device）
     */
    String caption;

    /**
     * 厂商 ID（SEAGATE、WD）
     */
    String vendorID;

    /**
     * 产品 ID
     */
    String productID;

    /**
     * 序列号
     */
    String serialNumber;

    /**
     * 固件版本
     */
    String firmwareRevision;

    /**
     * 介质类型（SSD、HDD、Unspecified）
     */
    String mediaType;

    /**
     * 总容量（字节数）
     */
    Long totalSpace;

    /**
     * 总容量说明（3.64 TB、16.37 TB）
     */
    String totalSpaceString;

    /**
     * 分区数量（1、2、3）
     */
    Long partitions;

    /**
     * 磁盘在当前系统内的连接位置（Fixed、External）
     */
    String currentConnectionType;

    /**
     * 磁盘在当前系统内的总线类型（NVMe、SATA、USB）
     */
    String currentBusType;

    /**
     * 磁盘在当前系统内的接口类型（SCSI、IDE、USB）
     */
    String currentInterfaceType;

    /**
     * 磁盘在当前系统内的 PNPDeviceID
     */
    String currentPNPDeviceID;

    /**
     * 磁盘在当前系统内的 DeviceID（\\.\PHYSICALDRIVE0、\\.\PHYSICALDRIVE1、\\.\PHYSICALDRIVE2）
     */
    String currentDeviceID;

    /**
     * 磁盘在当前系统内的 Index（0、1、2）
     */
    Integer currentIndex;

    /**
     * 磁盘在当前系统内的 Name（\\.\PHYSICALDRIVE0、\\.\PHYSICALDRIVE1、\\.\PHYSICALDRIVE2）
     */
    String currentName;

    /**
     * 磁盘在当前系统内的 Status（OK）
     */
    String currentStatus;

    /**
     * 创建时间
     */
    Long creationTime = System.currentTimeMillis();

    /**
     * 最后更新时间
     */
    Long lastUpdateTime = System.currentTimeMillis();

}
