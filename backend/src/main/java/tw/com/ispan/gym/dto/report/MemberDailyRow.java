package tw.com.ispan.gym.dto.report;

import java.time.LocalDate;

/**
 * 每日新會員聚合列（JPQL constructor expression 投影用）。
 * 只含有註冊的日期；無人註冊的日期不產生列，趨勢呈現端自行決定補零或斷線。
 */
public record MemberDailyRow(
        LocalDate statDate,
        Long newMembers) {
}
