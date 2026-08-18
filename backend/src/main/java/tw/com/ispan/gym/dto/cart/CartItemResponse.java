package tw.com.ispan.gym.dto.cart;

import java.math.BigDecimal;

public record CartItemResponse(
        Integer cartItemId,
        Integer productId,
        String productName,
        String imageUrl,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal) {}

