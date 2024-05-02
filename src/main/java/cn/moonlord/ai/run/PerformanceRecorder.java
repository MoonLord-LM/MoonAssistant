package cn.moonlord.ai.run;

import cn.moonlord.ai.web.vo.PerformanceVO;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

@Data
@Slf4j
@Component
public class PerformanceRecorder {

    private final PerformanceVO performance = new PerformanceVO();

    private volatile Long lastRefreshTime = System.currentTimeMillis();

    @SneakyThrows
    @Async
    @Scheduled(fixedRate = 3 * 1000)
    public void record() {
        HardwareAbstractionLayer hardware = new SystemInfo().getHardware();
        OperatingSystem operatingSystem = new SystemInfo().getOperatingSystem();

        String computerName = operatingSystem.getNetworkParams().getHostName();
        double cpuLoad = hardware.getProcessor().getSystemCpuLoad(1000);
        double availableMemory = hardware.getMemory().getAvailable();
        double totalMemory = hardware.getMemory().getTotal();
        double availableDisk = operatingSystem.getFileSystem().getFileStores().get(0).getUsableSpace();
        double totalDisk = operatingSystem.getFileSystem().getFileStores().get(0).getTotalSpace();

        Long cpu = Math.round(cpuLoad * 100);
        Long memory = Math.round((totalMemory - availableMemory) / totalMemory * 100);
        Long disk = Math.round((totalDisk - availableDisk) / totalDisk * 100);
        performance.setComputerName(computerName);
        performance.addRecord(cpu, memory, disk);
        lastRefreshTime = System.currentTimeMillis();
    }

}
