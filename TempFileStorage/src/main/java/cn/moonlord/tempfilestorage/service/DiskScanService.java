package cn.moonlord.tempfilestorage.service;

import cn.moonlord.tempfilestorage.vo.DiskHardwareVO;
import cn.moonlord.tempfilestorage.vo.LogicalVolumeVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class DiskScanService {

    @SneakyThrows
    public CopyOnWriteArrayList<DiskHardwareVO> getDiskHardware() {
        Process p1 = new ProcessBuilder("powershell.exe", "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-CimInstance Win32_DiskDrive | ConvertTo-Json;").start();
        AtomicReference<CopyOnWriteArrayList<DiskHardwareVO>> disks1 = new AtomicReference<>(new CopyOnWriteArrayList<>());
        CompletableFuture<String> outputFuture1 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p1.getInputStream(), StandardCharsets.UTF_8);
                log.debug("out1: {}", temp);
                disks1.set(DiskHardwareVO.mapper.readValue(temp, new TypeReference<>() {}));
                log.debug("disks1: {}", disks1);
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<String> errorFuture1 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p1.getErrorStream(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(temp)) {
                    log.error("error1: {}", temp);
                    throw new RuntimeException(temp);
                }
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture.allOf(outputFuture1, errorFuture1).join();
        p1.waitFor(30, TimeUnit.SECONDS);

        Process p2 = new ProcessBuilder("powershell.exe", "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-PhysicalDisk | ConvertTo-Json;").start();
        AtomicReference<CopyOnWriteArrayList<DiskHardwareVO>> disks2 = new AtomicReference<>(new CopyOnWriteArrayList<>());
        CompletableFuture<String> outputFuture2 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p2.getInputStream(), StandardCharsets.UTF_8);
                log.debug("out2: {}", temp);
                disks2.set(DiskHardwareVO.mapper.readValue(temp, new TypeReference<>() {}));
                log.debug("disks2: {}", disks2);
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<String> errorFuture2 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p2.getErrorStream(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(temp)) {
                    log.error("error2: {}", temp);
                    throw new RuntimeException(temp);
                }
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture.allOf(outputFuture2, errorFuture2).join();
        p2.waitFor(30, TimeUnit.SECONDS);

        for (int i = 0; i < disks1.get().size(); i++) {
            for (int j = 0; j < disks2.get().size(); j++) {
                if (disks1.get().get(i).getCurrentIndex().equals(disks2.get().get(j).getCurrentIndex())) {
                    disks1.get().get(i).setMediaType(disks2.get().get(j).getMediaType());
                    disks1.get().get(i).setCurrentBusType(disks2.get().get(j).getCurrentBusType());
                    break;
                }
            }
        }

        disks1.get().sort(Comparator.comparing(DiskHardwareVO::getCurrentIndex));
        return disks1.get();
    }

    @SneakyThrows
    public CopyOnWriteArrayList<LogicalVolumeVO> getLogicalVolume() {
        Process p1 = new ProcessBuilder("powershell.exe", "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; Get-CimInstance Win32_LogicalDisk | Select *, @{n='DiskHardwareIndex'; e={(Get-Partition -DriveLetter $_.DeviceID.Trim(':') ).DiskNumber }} | ConvertTo-Json;").start();
        AtomicReference<CopyOnWriteArrayList<LogicalVolumeVO>> disks1 = new AtomicReference<>(new CopyOnWriteArrayList<>());
        CompletableFuture<String> outputFuture1 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p1.getInputStream(), StandardCharsets.UTF_8);
                log.debug("out1: {}", temp);
                disks1.set(LogicalVolumeVO.mapper.readValue(temp, new TypeReference<>() {}));
                log.debug("disks1: {}", disks1);
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<String> errorFuture1 = CompletableFuture.supplyAsync(() -> {
            try {
                String temp = IOUtils.toString(p1.getErrorStream(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(temp)) {
                    log.error("error1: {}", temp);
                    throw new RuntimeException(temp);
                }
                return temp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture.allOf(outputFuture1, errorFuture1).join();
        p1.waitFor(30, TimeUnit.SECONDS);

        CopyOnWriteArrayList<DiskHardwareVO> hardware = getDiskHardware();
        for (int i = 0; i < disks1.get().size(); i++) {
            for (int j = 0; j < hardware.size(); j++) {
                if (disks1.get().get(i).getHardwareIndex().equals(hardware.get(j).getCurrentIndex())) {
                    disks1.get().get(i).setHardware(hardware.get(j));
                    break;
                }
            }
        }

        disks1.get().sort(Comparator.comparing(LogicalVolumeVO::getDeviceID));
        return disks1.get();
    }

}
