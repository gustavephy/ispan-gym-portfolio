package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String message) {
        super(HttpStatus.CONFLICT, "BUSINESS_RULE_VIOLATION", message);
    }
}