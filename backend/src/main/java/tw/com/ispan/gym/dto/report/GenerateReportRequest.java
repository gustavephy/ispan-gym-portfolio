package tw.com.ispan.gym.dto.report;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import tw.com.ispan.gym.enums.report.ReportType;

/** ?????梯”?eriodStart / periodEnd ?箏?剖撠曄??交????*/
public record GenerateReportRequest(
        @NotNull ReportType reportType,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd) {
}

