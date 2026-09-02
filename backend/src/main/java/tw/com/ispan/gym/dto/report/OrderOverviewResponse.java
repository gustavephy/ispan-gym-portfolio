package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 即時營運總覽（不落快照）：訂單量／營收／分布之外，
 * 一併帶回退款與新會員、新訂閱數，讓儀表板一次請求湊齊 KPI 卡列。
 */
public record OrderOverviewResponse(
        LocalDateTime from,
        LocalDateTime to,
        long totalOrders,
        BigDecimal totalRevenue,
        Map<String, Long> countByStatus,
        Map<String, BigDecimal> revenueByStatus,
        Map<String, Long> countByPaymentMethod,
        long refundCount,
        BigDecimal refundAmount,
        long newMemberCount,
        long newSubscriptionCount) {}
