package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** Registration ticket 不存在／已使用／已作廢／已過期，一律同一個公開理由 */
public class OAuthRegistrationTicketInvalidException extends ApiException {
    public OAuthRegistrationTicketInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, "OAUTH_REGISTRATION_TICKET_INVALID", message);
    }
}
