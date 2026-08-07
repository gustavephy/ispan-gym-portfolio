package tw.com.ispan.gym.cart.dto;

import java.math.BigDecimal;

public record CartItemResponse(
        Long cartItemId,
        Integer productId,
        String productType,
        String productName,
        String imageUrl,
        BigDecimal unitPrice,
        Long quantity,
        BigDecimal subtotal) {}

