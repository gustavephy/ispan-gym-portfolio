package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 商品／私教課銷售聚合列（JPQL constructor expression 承接）。
 * productId 與 courseId 恰一非空（order_item 雙 FK 的 DB CHECK 保證），
 * 品項型別由 service 依非空側判別。
 */
public record ProductSalesRow(
        Integer productId,
        Integer courseId,
        String productName,
        Long quantitySold,
        BigDecimal grossAmount) {
}
