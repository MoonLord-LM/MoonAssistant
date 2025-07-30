package cn.moonlord.stock.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class EastMoneyUtil {

    /**
     * 利润表
     */
    public static ResponseEntity<String> getIncomeStatementReport(String stockCode) {
        String url = "https://datacenter.eastmoney.com/securities/api/data/get" +
                "?type=RPT_F10_FINANCE_GINCOMEQC" +
                "&sty=PC_F10_GINCOMEQC" +
                "&filter=(SECUCODE=\"" + stockCode + ".SH\")" +
                "&p=1" +
                "&ps=200" +
                "&sr=-1" +
                "&st=REPORT_DATE" +
                "&source=HSF10" +
                "&client=PC";
        log.info("getIncomeStatementReport url: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://emweb.securities.eastmoney.com/pc_hsf10/pages/index.html?type=web&code=SH" + stockCode + "&color=b");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        log.info("getIncomeStatementReport stock: {}, status: {}", stockCode, response.getStatusCode());
        log.trace("getIncomeStatementReport response: {}", response.getBody());
        return response;
    }

    /**
     * 资产负债表
     */
    public static ResponseEntity<String> getBalanceSheetReport(String stockCode) {
        String url = "https://datacenter.eastmoney.com/securities/api/data/get" +
                "?type=RPT_F10_FINANCE_GBALANCE" +
                "&sty=F10_FINANCE_GBALANCE" +
                "&filter=(SECUCODE=\"" + stockCode + ".SH\")" +
                "&p=1" +
                "&ps=200" +
                "&sr=-1" +
                "&st=REPORT_DATE" +
                "&source=HSF10" +
                "&client=PC";
        log.info("getBalanceSheetReport url: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://emweb.securities.eastmoney.com/pc_hsf10/pages/index.html?type=web&code=SH" + stockCode + "&color=b");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        log.info("getBalanceSheetReport stock: {}, status: {}", stockCode, response.getStatusCode());
        log.trace("getBalanceSheetReport response: {}", response.getBody());
        return response;
    }

}
