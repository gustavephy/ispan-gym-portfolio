package tw.com.ispan.gym.order.dto;

import tw.com.ispan.gym.order.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        String merchantTradeNo,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt,
        List<OrderItemResponse> items) {}

