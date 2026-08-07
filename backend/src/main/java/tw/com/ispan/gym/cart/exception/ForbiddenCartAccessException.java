package tw.com.ispan.gym.cart.exception;

public class ForbiddenCartAccessException extends RuntimeException {
    public ForbiddenCartAccessException(Long cartItemId) { super("?⊥???甇方頃?抵??: " + cartItemId); }
}

