package tw.com.ispan.gym.dto.payment;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.payment.PaymentWebhook;

/**
 * 後台「金流回呼（Webhook）」列表用；每筆對應收到的一次綠界付款結果通知
 * （或模擬付款留下的一筆）。{@code payloadPreview} 為原始 payload 截斷後的前段，
 * 排查卡住的付款時看得到綠界實際送了什麼。
 */
public record AdminPaymentWebhookResponse(
        Integer id,
        String provider,
        String eventKey,
        String merchantTradeNo,
        String tradeNo,
        Boolean checkMacValid,
        String processingStatus,
        String errorMessage,
        String payloadPreview,
        LocalDateTime receivedAt,
        LocalDateTime processedAt) {

    private static final int PREVIEW_LIMIT = 500;

    public static AdminPaymentWebhookResponse from(PaymentWebhook w) {
        String payload = w.getPayload();
        String preview = payload == null ? null
                : (payload.length() <= PREVIEW_LIMIT ? payload : payload.substring(0, PREVIEW_LIMIT) + "…");
        return new AdminPaymentWebhookResponse(w.getId(), w.getProvider(), w.getEventKey(), w.getMerchantTradeNo(),
                w.getTradeNo(), w.getCheckMacValid(), w.getProcessingStatus(), w.getErrorMessage(), preview,
                w.getReceivedAt(), w.getProcessedAt());
    }
}
