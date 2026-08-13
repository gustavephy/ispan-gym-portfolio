package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import tw.com.ispan.gym.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long orderId,
        String merchantTradeNo,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt) {}

