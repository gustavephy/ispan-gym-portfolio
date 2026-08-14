package tw.com.ispan.gym.dto.report;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import tw.com.ispan.gym.enums.report.ReportType;

/** 生成報表請求。periodStart / periodEnd 為含頭含尾的日期區間 */
public record GenerateReportRequest(
        @NotNull ReportType reportType,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd) {
}
