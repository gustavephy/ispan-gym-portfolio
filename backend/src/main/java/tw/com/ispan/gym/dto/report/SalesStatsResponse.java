package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import tw.com.ispan.gym.enums.report.ReportItemType;

/** ?單?蝯梯?嚗??賢翰?改?嚗???+ 憿?蝭拚?????? */
public record SalesStatsResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportItemType productType,
        Long totalQuantity,
        BigDecimal grossAmount,
        List<ReportSalesItemResponse> ranking) {
}

