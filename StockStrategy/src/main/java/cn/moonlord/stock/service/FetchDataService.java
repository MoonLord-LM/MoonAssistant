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
import java.util.LinkedHashMap;

@Slf4j
@Component
public class FetchDataService {

    @SneakyThrows
    @PostConstruct
    public void init() {
        LinkedHashMap<String, FinancialReport> reports = new LinkedHashMap<>();
        ResponseEntity<String> response = EastMoneyUtil.getIncomeStatementReport("600519");
        JsonNode data = (new ObjectMapper()).readTree(response.getBody()).get("result").get("data");
        for (JsonNode report : data) {
            Date reportDate = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).parse(report.get("REPORT_DATE").asText());
            Integer year = reportDate.toInstant().atZone(ZoneId.systemDefault()).getYear();
            Integer season = reportDate.toInstant().atZone(ZoneId.systemDefault()).getMonthValue() / 3;
            FinancialReport fr = new FinancialReport();
            fr.setYear(year);
            fr.setSeason(season);
            fr.setTotalOperatingRevenue(report.get("TOTAL_OPERATE_INCOME").asDouble());
            fr.setParentNetProfit(report.get("PARENT_NETPROFIT").asDouble());
            fr.setAdjustedParentNetProfit(report.get("DEDUCT_PARENT_NETPROFIT").asDouble());
            reports.put(year + "-" + season, fr);
        }
        response = EastMoneyUtil.getBalanceSheetReport("600519");
        data = (new ObjectMapper()).readTree(response.getBody()).get("result").get("data");
        for (JsonNode report : data) {
            Date reportDate = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")).parse(report.get("REPORT_DATE").asText());
            Integer year = reportDate.toInstant().atZone(ZoneId.systemDefault()).getYear();
            Integer season = reportDate.toInstant().atZone(ZoneId.systemDefault()).getMonthValue() / 3;
            FinancialReport fr = reports.get(year + "-" + season);
            if (fr == null) {
                log.info("empty key: {}", year + "-" + season);
                continue;
            }
            fr.setTotalAssets(report.get("TOTAL_ASSETS").asDouble());
            fr.setTotalLiabilities(report.get("TOTAL_LIABILITIES").asDouble());
            fr.setNonControllingInterest(report.get("MINORITY_EQUITY").asDouble());
        }
        for (FinancialReport fr : reports.values()) {
            log.info("Financial Report: {}", fr);
        }
    }

}
