package cn.moonlord.stock.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class EastMoneyUtil {

    /**
     * 获取单季度财务报表
     */
    public static ResponseEntity<String> getQuarterFinancialReport(String stockCode) {
        String url = "https://datacenter.eastmoney.com/securities/api/data/v1/get" +
                "?reportName=RPT_F10_QTR_MAINFINADATA" +
                "&columns=ALL" +
                "&filter=(SECUCODE=\"" + stockCode + ".SH\"))" +
                "&pageNumber=1" +
                "&pageSize=200" +
                "&sortTypes=-1" +
                "&sortColumns=REPORT_DATE" +
                "&source=HSF10" +
                "&client=PC";
        log.info("getQuarterFinancialReport url: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://emweb.securities.eastmoney.com/pc_hsf10/pages/index.html?type=web&code=SH" + stockCode + "&color=b");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        log.info("getQuarterFinancialReport stock: {}, status: {}", stockCode, response.getStatusCode());
        return response;
    }

}
