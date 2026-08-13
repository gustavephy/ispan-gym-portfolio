package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

/**
 * 涵蓋「呼叫 AI 供應商失敗」與「AI 回應無法解析成預期 JSON」兩種情境，
 * 對前端而言都是同一種制式訊息：AI 服務暫時不可用，不暴露供應商內部細節。
 */
public class AiServiceUnavailableException extends ApiException {
    public AiServiceUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_UNAVAILABLE", "AI 服務暫時無法使用，請稍後再試");
    }
}
