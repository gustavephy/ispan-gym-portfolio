package tw.com.ispan.gym.cart.exception;

public class InvalidQuantityException extends RuntimeException {
    public InvalidQuantityException() { super("商品數量必須大於或等於 1"); }
}

