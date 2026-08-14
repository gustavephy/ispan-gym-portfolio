package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import tw.com.ispan.gym.enums.report.ReportType;

/** 單份報表：主檔總計＋排行明細。staleAt 非 null＝生成後發生過退款，建議重新生成 */
public record ReportDetailResponse(
        Integer id,
        ReportType reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        LocalDateTime generatedAt,
        LocalDateTime staleAt,
        List<ReportSalesItemResponse> items) {
}
