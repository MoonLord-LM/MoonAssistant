package cn.moonlord.tempfilestorage.vo;

import cn.moonlord.tempfilestorage.model.LogicalVolume;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 物理磁盘信息转换类
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class LogicalVolumeVO extends LogicalVolume {

    /**
     * 将 Get-CimInstance Win32_LogicalDisk 的结果转换为 LogicalVolumeVO
     */
    public static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new SimpleModule().addDeserializer(String.class, new StringDeserializer() {
            @Override
            public String deserialize(JsonParser p, DeserializationContext dc) throws IOException {
                String value = super.deserialize(p, dc);
                return value == null ? null : value.trim();
            }
        }));
    }

    @JsonAlias({"DiskHardwareIndex"})
    private Integer hardwareIndex;

    @JsonAlias({"VolumeSerialNumber"})
    private String serialNumber;

    @JsonAlias({"VolumeName"})
    private String name;

    @JsonAlias({"Size"})
    private Long totalSpace;

    @SuppressWarnings("unused")
    public void setTotalSpace(final Long totalSpace) {
        this.totalSpace = totalSpace;
        if (totalSpace != null) {
            double size = totalSpace;
            String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
            int unitIndex = 0;
            while (size >= 1024 && unitIndex < units.length - 1) {
                size /= 1024;
                unitIndex++;
            }
            super.setTotalSpaceString(String.format("%.2f %s", size, units[unitIndex]));
        }
    }

    @SuppressWarnings("unused")
    public void setFreeSpace(final Long freeSpace) {
        super.setFreeSpace(freeSpace);
        if (freeSpace != null) {
            double size = freeSpace;
            String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
            int unitIndex = 0;
            while (size >= 1024 && unitIndex < units.length - 1) {
                size /= 1024;
                unitIndex++;
            }
            super.setFreeSpaceString(String.format("%.2f %s", size, units[unitIndex]));
        }
    }

}
