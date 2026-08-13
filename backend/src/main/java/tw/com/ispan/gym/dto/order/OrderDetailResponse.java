package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import tw.com.ispan.gym.enums.order.OrderStatus;
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

