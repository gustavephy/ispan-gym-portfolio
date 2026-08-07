package com.wpowergym.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long orderItemId,
        Long productId,
        String productNameAtBuy,
        BigDecimal priceAtBuy,
        Long quantity,
        BigDecimal lineAmount) {}
