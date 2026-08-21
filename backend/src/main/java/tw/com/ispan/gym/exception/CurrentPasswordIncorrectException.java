package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 修改密碼時目前密碼比對失敗。故意不沿用 401/UNAUTHENTICATED——那個狀態碼在這個
 * 專案已經是「JWT 本身失效」的通用意義，前端可能因此整頁導回登入頁；打錯目前密碼
 * 不該有那種效果，使用者明明還在登入狀態。
 */
public class CurrentPasswordIncorrectException extends ApiException {
    public CurrentPasswordIncorrectException(String message) {
        super(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", message);
    }
}
