package tw.com.ispan.gym.exception;

public class ForbiddenSlotAccessException extends RuntimeException {
    public ForbiddenSlotAccessException(Integer slotId) {
        super("無權存取此教練時段：" + slotId);
    }
}