package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import tw.com.ispan.gym.enums.report.ReportType;

/** 歷史報表列表列。staleAt 非 null＝生成後涵蓋區間內發生過退款，建議重新生成 */
public record ReportSummaryResponse(
        Integer id,
        ReportType reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        LocalDateTime generatedAt,
        LocalDateTime staleAt) {
}
