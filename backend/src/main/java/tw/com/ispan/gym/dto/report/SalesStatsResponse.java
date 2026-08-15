package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import tw.com.ispan.gym.enums.report.ReportItemType;

/** 即時統計（不落快照）：總計 + 品項型別篩選後的排行 */
public record SalesStatsResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportItemType productType,
        Long totalQuantity,
        BigDecimal grossAmount,
        List<ReportSalesItemResponse> ranking) {
}
