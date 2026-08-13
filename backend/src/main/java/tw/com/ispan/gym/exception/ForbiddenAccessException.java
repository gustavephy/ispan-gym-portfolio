package tw.com.ispan.gym.exception;

import org.springframework.http.HttpStatus;

/**
 * 使用者嘗試存取或操作一筆不屬於自己的資源時丟出，統一以 403 回應，
 * code 固定為 FORBIDDEN_ACCESS。
 *
 * <p>
 * 訊息內容由呼叫端自行組成，因為不同資源被拒絕存取時，
 * 該提示使用者的具體內容不一樣（例如：無權操作此教練時段、無權操作此預約），
 * 這個類別只負責統一回應格式，不綁定特定業務情境。
 */
public class ForbiddenAccessException extends ApiException {
    public ForbiddenAccessException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCESS", message);
    }
}