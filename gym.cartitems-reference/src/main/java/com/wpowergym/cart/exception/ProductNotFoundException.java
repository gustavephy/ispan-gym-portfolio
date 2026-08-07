package com.wpowergym.cart.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Long productId) { super("商品不存在: " + productId); }
}
