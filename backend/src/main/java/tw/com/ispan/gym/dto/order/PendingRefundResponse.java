package tw.com.ispan.gym.dto.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PendingRefundResponse(
        Long refundId,
        Long orderId,
        String merchantTradeNo,
        String refundNo,
        BigDecimal amount,
        String reason,
        LocalDateTime requestedAt) {}
