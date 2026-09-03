package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import tw.com.ispan.gym.enums.report.ReportType;

/**
 * 單份報表：主檔總計＋排行明細＋延伸統計。staleAt 非 null＝生成後發生過退款，建議重新生成。
 * 延伸統計（statusBreakdown 之後的欄位）為 2026-08-17 擴充；舊快照無此資料，
 * 純量為 null、清單為空，呈現端須容忍。
 * voidedAt 非 null＝已作廢（2026-09-03 加入；快照不提供刪除，誤生成只能作廢），
 * voidReason 為作廢原因（選填，可為 null）。
 */
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
        List<ReportSalesItemResponse> items,
        List<ReportBreakdownResponse> statusBreakdown,
        List<ReportBreakdownResponse> paymentBreakdown,
        List<ReportDailyTotalResponse> dailyTotals,
        Long refundCount,
        BigDecimal refundAmount,
        Long newMemberCount,
        Long newSubscriptionCount,
        LocalDateTime voidedAt,
        String voidReason) {
}
