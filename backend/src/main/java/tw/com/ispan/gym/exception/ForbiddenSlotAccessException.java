package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenSlotAccessException extends ApiException {
    public ForbiddenSlotAccessException(Integer slotId) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_SLOT_ACCESS", "無權存取此教練時段：" + slotId);
    }
}