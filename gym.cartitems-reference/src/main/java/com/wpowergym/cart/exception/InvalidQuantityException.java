package com.wpowergym.cart.exception;

public class InvalidQuantityException extends RuntimeException {
    public InvalidQuantityException() { super("quantity 必須大於或等於 1"); }
}
