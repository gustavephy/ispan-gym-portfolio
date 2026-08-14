package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/** 訂閱銷售聚合列（訂閱單無 order_item 明細，直接聚合 orders） */
public record SubscriptionSalesRow(
        Integer planId,
        String planName,
        Long quantitySold,
        BigDecimal grossAmount) {
}
