package tw.com.ispan.gym.dto.order;

import java.time.LocalDateTime;

public record OrderStatusHistoryResponse(
        String fromStatus,
        String toStatus,
        String changeSource,
        String note,
        LocalDateTime changedAt) {}
