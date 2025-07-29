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
public class FinancialReport {

    /**
     * 年份（2025）
     */
    Integer year;

    /**
     * 季度（1、2、3、4）
     */
    Integer season;

    /**
     * 营业收入（万元）
     */
    Double operatingRevenue;

    /**
     * 净利润（万元）
     */
    Double netProfit;

    /**
     * 扣非净利润（万元）
     */
    Double adjustedNetProfit;

    /**
     * 总资产（万元）
     */
    Double totalAssets;

    /**
     * 总负债（万元）
     */
    Double totalLiabilities;

    /**
     * 营业收入净利润率（NPM, Net Profit Margin） = 净利润 / 营业收入
     */
    public Double getNetProfitMargin() {
        if (operatingRevenue == null || netProfit == null) {
            return null;
        }
        return (double) netProfit / operatingRevenue;
    }

    /**
     * 非经常性损益（万元）= 净利润 - 扣非净利润
     */
    public Double getNonRecurringProfitAndLoss() {
        if (netProfit == null || adjustedNetProfit == null) {
            return null;
        }
        return netProfit - adjustedNetProfit;
    }

    /**
     * 资产周转率（ATR, Asset Turnover Ratio） = 营业收入 / 总资产
     */
    public Double getAssetTurnoverRatio() {
        if (operatingRevenue == null || totalAssets == null) {
            return null;
        }
        return (double) operatingRevenue / totalAssets;
    }

    /**
     * 资产收益率（ROA, Return on Assets） = 净利润 / 总资产
     */
    public Double getReturnOnAssets() {
        if (netProfit == null || totalAssets == null) {
            return null;
        }
        return (double) netProfit / totalAssets;
    }

    /**
     * 资产负债率（DAR, Debt to Asset Ratio） = 总负债 / 总资产
     */
    public Double getDebtRatio() {
        if (totalAssets == null || totalLiabilities == null) {
            return null;
        }
        return (double) totalLiabilities / totalAssets;
    }

    /**
     * 净资产（万元） = 总资产 - 总负债
     */
    public Double getNetAssets() {
        if (totalAssets == null || totalLiabilities == null) {
            return null;
        }
        return totalAssets - totalLiabilities;
    }

    /**
     * 净资产收益率（ROE, Return on Equity） = 净利润 / 净资产
     */
    public Double getReturnOnEquity() {
        if (netProfit == null || getNetAssets() == null) {
            return null;
        }
        return (double) netProfit / getNetAssets();
    }

    /**
     * 负债权益比率（DER, Debt to Equity Ratio） = 总负债 / 净资产
     */
    public Double getDebtToEquityRatio() {
        if (totalLiabilities == null || getNetAssets() == null) {
            return null;
        }
        return (double) totalLiabilities / getNetAssets();
    }

}
