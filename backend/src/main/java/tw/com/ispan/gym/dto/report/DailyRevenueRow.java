package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日營收聚合列（JPQL constructor expression 投影用）。
 * 只含有營收的日期；無單日不產生列，趨勢呈現端自行決定補零或斷線。
 */
public record DailyRevenueRow(
        LocalDate statDate,
        Long orderCount,
        BigDecimal revenue) {
}
