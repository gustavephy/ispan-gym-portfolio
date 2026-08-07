package tw.com.ispan.gym.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long orderItemId,
        Integer productId,
        String productNameAtBuy,
        BigDecimal priceAtBuy,
        Long quantity,
        BigDecimal lineAmount) {}

