package tw.com.ispan.gym.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record OrderOverviewResponse(
        LocalDateTime from,
        LocalDateTime to,
        long totalOrders,
        BigDecimal totalRevenue,
        Map<String, Long> countByStatus,
        Map<String, BigDecimal> revenueByStatus,
        Map<String, Long> countByPaymentMethod) {}
