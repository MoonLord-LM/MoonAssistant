package cn.moonlord.tempfilestorage.vo;

import cn.moonlord.tempfilestorage.model.DiskHardware;
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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 物理磁盘信息转换类
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DiskHardwareVO extends DiskHardware {

    /**
     * 将 Get-CimInstance Win32_DiskDrive 和 Get-PhysicalDisk 的结果转换为 DiskHardwareVO
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

    @SuppressWarnings("unused")
    public void setMediaType(final String mediaType) {
        if (mediaType.startsWith("Fixed")) {
            super.setCurrentConnectionType("Fixed");
        } else if (mediaType.startsWith("External")) {
            super.setCurrentConnectionType("External");
        } else if (mediaType.startsWith("SSD")) {
            super.setMediaType("SSD");
        } else if (mediaType.startsWith("HDD")) {
            super.setMediaType("HDD");
        } else if (mediaType.startsWith("Unspecified")) {
            super.setMediaType("Unspecified");
        }
    }

    @JsonAlias({"Size"})
    private Long totalSpace;

    @SuppressWarnings("unused")
    public void setTotalSpace(final Long totalSpace) {
        this.totalSpace = totalSpace;
        if (totalSpace != null) {
            double size = totalSpace;
            String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB", "RB", "QB"};
            int unitIndex = 0;
            while (size >= 1024 && unitIndex < units.length - 1) {
                size /= 1024;
                unitIndex++;
            }
            super.setTotalSpaceString(String.format("%.2f %s", size, units[unitIndex]));
        }
    }

    @JsonAlias({"BusType"})
    private String currentBusType;

    @JsonAlias({"InterfaceType"})
    private String currentInterfaceType;

    @JsonAlias({"PNPDeviceID"})
    private String currentPNPDeviceID;

    @SuppressWarnings("unused")
    public void setCurrentPNPDeviceID(final String currentPNPDeviceID) {
        this.currentPNPDeviceID = currentPNPDeviceID;
        if (!StringUtils.hasText(currentPNPDeviceID)) {
            return;
        }
        String tmp = currentPNPDeviceID;
        if (tmp.startsWith("SCSI\\")) {
            tmp = tmp.substring("SCSI\\".length());
        } else if (tmp.startsWith("USBSTOR\\")) {
            tmp = tmp.substring("USBSTOR\\".length());
        }
        if (tmp.startsWith("DISK&VEN_") && tmp.contains("&PROD_")) {
            super.setVendorID(tmp.substring("DISK&VEN_".length(), tmp.indexOf("&PROD_")));
            tmp = tmp.substring("DISK&VEN_".length() + super.getVendorID().length() + "&PROD_".length());
        }
        if (tmp.contains("\\")) {
            tmp = tmp.substring(0, tmp.indexOf("\\"));
            tmp = tmp.replaceAll("_", " ");
            tmp = tmp.replaceAll("&", " ");
            super.setProductID(tmp);
        }
    }

    @JsonAlias({"DeviceID"})
    private String currentDeviceID;

    @JsonAlias({"Index", "DeviceId"})
    private Integer currentIndex;

    @JsonAlias({"Name"})
    private String currentName;

    @JsonAlias({"Status"})
    private String currentStatus;

}
