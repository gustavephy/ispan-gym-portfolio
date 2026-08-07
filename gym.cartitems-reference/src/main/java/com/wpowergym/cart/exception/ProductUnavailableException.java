package com.wpowergym.cart.exception;

public class ProductUnavailableException extends RuntimeException {
    public ProductUnavailableException(Long productId) { super("商品目前未上架: " + productId); }
}
