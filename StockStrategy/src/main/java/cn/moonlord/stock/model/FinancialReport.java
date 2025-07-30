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
     * 合计营业收入
     */
    Double totalOperatingRevenue;

    /**
     * 归母净利润
     */
    Double parentNetProfit;

    /**
     * 扣非归母净利润
     */
    Double adjustedParentNetProfit;

    /**
     * 合计总资产
     */
    Double totalAssets;

    /**
     * 合计总负债
     */
    Double totalLiabilities;

    /**
     * 子公司少数股东权益
     */
    Double nonControllingInterest;

    /**
     * 营业收入归母净利润率 = 归母净利润 / 营业收入
     */
    public Double getParentNetProfitMargin() {
        if (totalOperatingRevenue == null || parentNetProfit == null) {
            return null;
        }
        return (double) parentNetProfit / totalOperatingRevenue;
    }

    /**
     * 营业收入扣非归母净利润率 = 扣非归母净利润 / 营业收入
     */
    public Double getAdjustedParentNetProfitMargin() {
        if (totalOperatingRevenue == null || adjustedParentNetProfit == null) {
            return null;
        }
        return (double) adjustedParentNetProfit / totalOperatingRevenue;
    }

    /**
     * 归母非经常性损益（万元）= 归母净利润 - 扣非归母净利润
     */
    public Double getNonRecurringProfitAndLoss() {
        if (parentNetProfit == null || adjustedParentNetProfit == null) {
            return null;
        }
        return parentNetProfit - adjustedParentNetProfit;
    }

    /**
     * 资产周转率（ATR, Asset Turnover Ratio） = 营业收入 / 总资产
     */
    public Double getAssetTurnoverRatio() {
        if (totalOperatingRevenue == null || totalAssets == null) {
            return null;
        }
        return (double) totalOperatingRevenue / totalAssets;
    }

    /**
     * 资产收益率（ROA, Return on Assets） = 归母净利润 / 总资产
     */
    public Double getReturnOnAssets() {
        if (parentNetProfit == null || totalAssets == null) {
            return null;
        }
        return (double) parentNetProfit / totalAssets;
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
     * 归母净资产（万元） = 总资产 - 总负债 - 少数股东权益
     */
    public Double getNetAssets() {
        if (totalAssets == null || totalLiabilities == null || nonControllingInterest == null) {
            return null;
        }
        return totalAssets - totalLiabilities - nonControllingInterest;
    }

    /**
     * 归母净资产收益率（ROE, Return on Equity） = 归母净利润 / 归母净资产
     */
    public Double getReturnOnEquity() {
        if (parentNetProfit == null || getNetAssets() == null) {
            return null;
        }
        return (double) parentNetProfit / getNetAssets();
    }

    /**
     * 负债权益比率（DER, Debt to Equity Ratio） = 总负债 / 归母净资产
     */
    public Double getDebtToEquityRatio() {
        if (totalLiabilities == null || getNetAssets() == null) {
            return null;
        }
        return (double) totalLiabilities / getNetAssets();
    }

}
