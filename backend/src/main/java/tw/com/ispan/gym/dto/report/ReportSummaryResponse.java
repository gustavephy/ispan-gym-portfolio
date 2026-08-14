package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import tw.com.ispan.gym.enums.report.ReportType;

/** 甇瑕?梯”?”??*/
public record ReportSummaryResponse(
        Integer id,
        ReportType reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        LocalDateTime generatedAt) {
}

