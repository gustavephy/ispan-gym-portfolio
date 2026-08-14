package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 訂單區間彙總（JPQL constructor expression 投影用）。
 * 區間內無訂單時 SUM 為 null，由 service 正規化為 0。
 */
public record OrderPeriodSummary(
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount) {
}
