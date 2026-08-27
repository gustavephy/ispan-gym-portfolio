package tw.com.ispan.gym.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.payment.PaymentRefund;

/** 後台「退款紀錄」列表用；{@code /refunds/pending} 只回待處理，這裡回全部狀態。 */
public record AdminPaymentRefundResponse(
        Integer refundId,
        Integer orderId,
        String merchantTradeNo,
        String refundNo,
        BigDecimal amount,
        String status,
        String reason,
        LocalDateTime requestedAt,
        LocalDateTime completedAt) {

    public static AdminPaymentRefundResponse from(PaymentRefund r) {
        var order = r.getPayment().getOrder();
        return new AdminPaymentRefundResponse(r.getId(), order.getOrderId(), order.getMerchantTradeNo(),
                r.getRefundNo(), r.getAmount(), r.getStatus(), r.getReason(),
                r.getRequestedAt(), r.getCompletedAt());
    }
}
