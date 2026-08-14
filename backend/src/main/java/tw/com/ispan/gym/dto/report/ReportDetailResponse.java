package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import tw.com.ispan.gym.enums.report.ReportType;

/** ?桐遢?梯”嚗蜓瑼?蝮?+ ?瑕???敦 */
public record ReportDetailResponse(
        Integer id,
        ReportType reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        LocalDateTime generatedAt,
        List<ReportSalesItemResponse> items) {
}

