package tw.com.ispan.gym.dto.cart;

import java.math.BigDecimal;

public record CartItemResponse(
        Long cartItemId,
        Integer productId,
        String productName,
        String imageUrl,
        BigDecimal unitPrice,
        Long quantity,
        BigDecimal subtotal) {}

