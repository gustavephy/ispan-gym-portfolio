package tw.com.ispan.gym.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.payment.PaymentTransaction;

/** 後台「交易紀錄」列表用；一筆對應一次金流交易（{@code payment_transaction}）。 */
public record AdminPaymentTransactionResponse(
        Integer id,
        Integer orderId,
        String orderNo,
        String merchantTradeNo,
        String tradeNo,
        String provider,
        String paymentMethod,
        BigDecimal amount,
        String currency,
        String status,
        Integer rtnCode,
        String rtnMsg,
        Boolean simulatePaid,
        LocalDateTime paidAt,
        LocalDateTime createdAt) {

    public static AdminPaymentTransactionResponse from(PaymentTransaction t) {
        return new AdminPaymentTransactionResponse(t.getId(), t.getOrder().getId(), t.getOrder().getOrderNo(),
                t.getMerchantTradeNo(), t.getTradeNo(), t.getProvider(), t.getPaymentMethod(), t.getAmount(),
                t.getCurrency(), t.getStatus(), t.getRtnCode(), t.getRtnMsg(), t.getSimulatePaid(),
                t.getPaidAt(), t.getCreatedAt());
    }
}
