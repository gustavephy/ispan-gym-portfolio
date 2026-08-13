package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @Size(max = 30) String couponCode,
        @Size(max = 50) String receiverName,
        @Size(max = 20) String receiverPhone,
        @Size(max = 200) String receiverAddress) {}

