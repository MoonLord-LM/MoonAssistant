package cn.moonlord.stock.service;

import cn.moonlord.stock.model.FinancialReport;
import cn.moonlord.stock.utils.EastMoneyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;

@Slf4j
@Component
public class FetchDataService {

    @SneakyThrows
    @PostConstruct
    public void init() {
        ResponseEntity<String> response = EastMoneyUtil.getQuarterFinancialReport("600519");
        log.info("response body: {}", response.getBody());
        JsonNode root = (new ObjectMapper()).readTree(response.getBody());
        JsonNode data = root.get("result").get("data");
        for (JsonNode report : data) {
            log.info("report: {}", report);
            FinancialReport fr = new FinancialReport();
            Date reportDate = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).parse(report.get("REPORT_DATE").asText());
            fr.setYear(reportDate.toInstant().atZone(ZoneId.systemDefault()).getYear());
            fr.setSeason(reportDate.toInstant().atZone(ZoneId.systemDefault()).getMonthValue() / 3);
            fr.setOperatingRevenue(report.get("TOTALOPERATEREVE").asDouble());
            fr.setNetProfit(report.get("PARENTNETPROFIT").asDouble());
            fr.setAdjustedNetProfit(report.get("DEDU_PARENT_PROFIT").asDouble());
            log.info("fr: {}", fr);
        }
        log.info("FetchDataService init");
    }

}
