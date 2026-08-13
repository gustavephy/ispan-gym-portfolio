package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 500) String reason) {}
