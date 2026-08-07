package com.wpowergym.order.dto;

import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @Size(max = 30) String couponCode,
        @Size(max = 50) String receiverName,
        @Size(max = 20) String receiverPhone,
        @Size(max = 200) String receiverAddress) {}
