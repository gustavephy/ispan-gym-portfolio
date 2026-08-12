package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 某個操作需要使用者明確確認才能繼續執行時丟出，統一以 409 回應，
 * code 固定為 CONFIRMATION_REQUIRED，讓前端能準確辨識「這個 409 該跳確認視窗、
 * 帶確認標記重送」，而不是單純顯示錯誤訊息。
 *
 * <p>
 * 訊息內容由呼叫端自行組成，因為不同情境需要提醒使用者的具體內容不一樣
 * （例如：時段已被預約、取消會消耗額度不予退還），這個類別只負責統一回應格式，
 * 不綁定特定業務情境。
 */
public class ConfirmationRequiredException extends ApiException {
    public ConfirmationRequiredException(String message) {
        super(HttpStatus.CONFLICT, "CONFIRMATION_REQUIRED",
                message);
    }
}