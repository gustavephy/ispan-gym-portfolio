package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** LINK／UNLINK Google 前必須重新驗證目前密碼，密碼錯誤或帳號沒有本機密碼皆用這個 */
public class ReauthenticationRequiredException extends ApiException {
    public ReauthenticationRequiredException(String message) {
        super(HttpStatus.UNAUTHORIZED, "REAUTHENTICATION_REQUIRED", message);
    }
}
