package cn.moonlord.stock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 财务报表信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockFinancialReport {

    /**
     * 年份（2025）
     */
    Long year;

    /**
     * 季度（1、2、3、4）
     */
    Long season;

    /**
     * 营业收入（万元）
     */
    Long operatingRevenue;

    /**
     * 净利润（万元）
     */
    Long netProfit;

    /**
     * 非经常性损益（万元）
     */
    Long nonRecurringProfitAndLoss;

    /**
     * 扣非净利润（万元） = 净利润 - 非经常性损益
     */
    Long adjustedNetProfit;

    /**
     * 总资产（万元）
     */
    Long totalAssets;

    /**
     * 总负债（万元）
     */
    Long totalLiabilities;

    /**
     * 净资产（万元） = 总资产 - 总负债
     */
    Long netAssets;

}
