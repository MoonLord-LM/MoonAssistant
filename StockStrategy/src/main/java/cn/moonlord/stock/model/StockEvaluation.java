package cn.moonlord.stock.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 股票分析信息
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockEvaluation {

    /**
     * 净资产收益率 (ROE, Return on Equity)
     * 公式：净利润 / 净资产
     * 说明：衡量公司利用股东投入资本产生利润的能力
     * 单位：百分比(%)
     */
    Double returnOnEquity;

    /**
     * 资产负债率 (DAR, Debt to Asset Ratio)
     * 公式：总负债 / 总资产
     * 说明：衡量公司的负债水平和财务风险
     * 单位：百分比(%)
     */
    Double debtToAssetRatio;

    /**
     * 资产收益率 (ROA, Return on Assets)
     * 公式：净利润 / 总资产
     * 说明：衡量公司利用总资产产生利润的能力
     * 单位：百分比(%)
     */
    Double returnOnAssets;

    /**
     * 市净率 (PB, Price to Book Ratio)
     * 公式：股价 / 每股净资产
     * 说明：衡量股票的市场价格相对于其账面价值的溢价水平
     * 单位：倍数
     */
    Double priceToBookRatio;

    /**
     * 市盈率 (PE, Price to Earnings Ratio)
     * 公式：股价 / 每股收益(EPS)
     * 说明：衡量投资者购买股票的回本时间
     * 单位：倍数
     */
    Double priceToEarningsRatio;

    /**
     * 分红支付率 (PR, Payout Ratio)
     * 公式：年度股息总额 / 年度净利润
     * 说明：衡量公司的股利政策和经营策略
     * 单位：百分比(%)
     */
    Double payoutRatio;

    /**
     * 股息率 (DY, Dividend Yield)
     * 公式：年度每股股息 / 股价
     * 说明：衡量投资者通过股息获得的投资回报率
     * 单位：百分比(%)
     */
    Double dividendYield;

}
