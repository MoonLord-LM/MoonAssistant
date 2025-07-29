package cn.moonlord.stock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 股票信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Stock {

    /**
     * 代码
     */
    String code;

    /**
     * 名称
     */
    String name;

    /**
     * 财报
     */
    List<FinancialReport> financialReport;

}
