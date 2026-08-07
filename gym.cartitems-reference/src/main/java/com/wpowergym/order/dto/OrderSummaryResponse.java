package com.wpowergym.order.dto;

import com.wpowergym.order.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long orderId,
        String merchantTradeNo,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt) {}
