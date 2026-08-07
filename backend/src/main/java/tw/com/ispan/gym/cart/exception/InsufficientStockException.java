package tw.com.ispan.gym.cart.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Integer productId, long requested, long available) {
        super("??摨怠?銝雲: productId=" + productId + ", requested=" + requested + ", available=" + available);
    }
}

