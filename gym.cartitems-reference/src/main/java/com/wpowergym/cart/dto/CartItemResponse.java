package com.wpowergym.cart.dto;

import com.wpowergym.product.entity.ProductType;
import java.math.BigDecimal;

public record CartItemResponse(
        Long cartItemId,
        Long productId,
        ProductType productType,
        String productName,
        String imageUrl,
        BigDecimal unitPrice,
        Long quantity,
        BigDecimal subtotal) {}
