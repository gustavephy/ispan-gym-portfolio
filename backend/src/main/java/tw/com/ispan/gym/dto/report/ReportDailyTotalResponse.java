package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 每日營收列（趨勢圖資料）；只含有營收的日期，缺日由呈現端補零或斷線 */
public record ReportDailyTotalResponse(
        LocalDate statDate,
        Long orderCount,
        BigDecimal revenue) {
}
