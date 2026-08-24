package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;

/**
 * 期間退款彙總（JPQL constructor expression 投影用）。
 * 區間內無退款時 SUM 為 null，由 service 正規化為 0。
 */
public record ReportRefundSummary(
        Long refundCount,
        BigDecimal refundAmount) {
}
