package com.wpowergym.cart.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long productId, long requested, long available) {
        super("商品庫存不足: productId=" + productId + ", requested=" + requested + ", available=" + available);
    }
}
