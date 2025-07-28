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
