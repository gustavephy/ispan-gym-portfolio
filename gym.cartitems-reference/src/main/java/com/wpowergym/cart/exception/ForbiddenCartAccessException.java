package com.wpowergym.cart.exception;

public class ForbiddenCartAccessException extends RuntimeException {
    public ForbiddenCartAccessException(Long cartItemId) { super("無權操作此購物車項目: " + cartItemId); }
}
