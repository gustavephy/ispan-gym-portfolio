package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/** 帳號 password_hash 為 null（OAuth-only）時嘗試修改密碼；只用在已登入端點，不適用忘記密碼（那裡要防列舉） */
public class PasswordNotConfiguredException extends ApiException {
    public PasswordNotConfiguredException(String message) {
        super(HttpStatus.CONFLICT, "PASSWORD_NOT_CONFIGURED", message);
    }
}
