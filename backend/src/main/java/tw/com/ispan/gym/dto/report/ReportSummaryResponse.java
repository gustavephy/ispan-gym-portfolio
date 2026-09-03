package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import tw.com.ispan.gym.enums.report.ReportType;

/**
 * 歷史報表列表列。staleAt 非 null＝生成後涵蓋區間內發生過退款，建議重新生成；
 * supersededAt 非 null＝已被較新版本取代的歷史版本（列表端點只回現行版，
 * 版本清單端點兩者都回）；voidedAt 非 null＝已作廢（誤生成，數字僅供追溯，
 * 列表端點預設不回，includeVoided=true 才回）。
 */
public record ReportSummaryResponse(
        Integer id,
        ReportType reportType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long orderCount,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        LocalDateTime generatedAt,
        LocalDateTime staleAt,
        LocalDateTime supersededAt,
        LocalDateTime voidedAt) {
}
