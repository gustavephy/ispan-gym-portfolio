package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 各商品分類營收聚合列（JPQL constructor expression 承接）。
 * 只涵蓋商品品項；私教課與會籍訂閱沒有商品分類，歸在 {@link RevenueCompositionRow}。
 */
public record CategoryRevenueRow(
        String categoryName,
        Long quantitySold,
        BigDecimal grossAmount) {
}
