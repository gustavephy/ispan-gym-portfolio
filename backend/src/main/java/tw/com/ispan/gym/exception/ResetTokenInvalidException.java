package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 重設密碼失敗的單一公開理由，涵蓋「不存在／已使用／已作廢／已過期／帳號已不符資格」
 * 全部五種情況——故意不區分，避免回應形狀被拿來反推是哪一種，等於間接洩漏帳號狀態。
 */
public class ResetTokenInvalidException extends ApiException {
    public ResetTokenInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID", message);
    }
}
