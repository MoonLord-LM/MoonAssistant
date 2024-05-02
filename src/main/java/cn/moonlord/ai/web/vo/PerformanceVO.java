package cn.moonlord.ai.web.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class PerformanceVO {

    @JsonIgnore
    private volatile CopyOnWriteArrayList<RecordVO> records = new CopyOnWriteArrayList<>();
    @JsonIgnore
    private String computerName;

    private volatile List<String> labels = new ArrayList<>();
    private volatile List<DatasetVO> datasets = new ArrayList<>();

    public synchronized void addRecord(@NotNull Long cpu, @NotNull Long memory, @NotNull Long disk) {
        records.add(new RecordVO(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), cpu, memory, disk));
        if (records.size() > 10L) {
            records.remove(0);
        }
        labels = records.stream().map(record -> record.time).toList();
        List<DatasetVO> tmp = new ArrayList<>();
        tmp.add(new DatasetVO().setType("bar").setLabel("CPU").setData(records.stream().map(record -> record.cpu).toList()));
        tmp.add(new DatasetVO().setType("bar").setLabel("Memory").setData(records.stream().map(record -> record.memory).toList()));
        tmp.add(new DatasetVO().setType("bar").setLabel("Disk").setData(records.stream().map(record -> record.disk).toList()));
        datasets = tmp;
    }

    @Data
    @Accessors(chain = true)
    @AllArgsConstructor
    @NoArgsConstructor
    private static class DatasetVO {
        private String type;
        private String label;
        private List<Long> data = new ArrayList<>();
        private List<String> backgroundColor = new ArrayList<>();
        private String borderColor = "#c0c0c0"; // gray
        private String borderWidth = "1";

        public DatasetVO setData(List<Long> data) {
            this.data = data;
            this.backgroundColor = data.stream().map(usage -> {
                if (usage > 90) {
                    return "#ec4555"; // red
                } else if (usage > 60) {
                    return "#ffd117"; // yellow
                } else if (usage > 30) {
                    return "#1d7efd"; // blue
                } else {
                    return "#299764"; // green
                }
            }).toList();
            return this;
        }
    }

    @Data
    @Accessors(chain = true)
    @AllArgsConstructor
    @NoArgsConstructor
    private static class RecordVO {
        private String time;
        private Long cpu;
        private Long memory;
        private Long disk;
    }

    @SneakyThrows
    @Override
    public String toString() {
        return (new ObjectMapper()).writeValueAsString(this);
    }

}
