package com.wpowergym.cart.exception;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(Long cartItemId) { super("購物車項目不存在: " + cartItemId); }
}
