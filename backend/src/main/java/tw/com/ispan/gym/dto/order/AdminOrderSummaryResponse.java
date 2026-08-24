package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.enums.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminOrderSummaryResponse(
        Integer orderId,
        String orderNo,
        String merchantTradeNo,
        Integer memberId,
        String memberName,
        String memberEmail,
        BigDecimal totalAmount,
        OrderStatus status,
        String paymentMethod,
        LocalDateTime createdAt,
        LocalDateTime paidAt) {}
