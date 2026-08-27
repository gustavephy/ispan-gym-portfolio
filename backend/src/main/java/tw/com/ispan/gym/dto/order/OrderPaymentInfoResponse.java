package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.payment.PaymentTransaction;

import java.time.LocalDateTime;

/**
 * ATM／超商代碼取號資訊，供訂單仍是 UNPAID 時前端顯示「請於期限內完成繳費」——
 * 見 {@link tw.com.ispan.gym.service.payment.EcpayService#handlePaymentInfo}。
 * 信用卡訂單、或 ATM/CVS 訂單尚未收到取號通知時皆為 {@code null}。
 */
public record OrderPaymentInfoResponse(
        String choosePayment,
        String bankCode,
        String vAccount,
        String paymentNo,
        LocalDateTime expireAt) {

    public static OrderPaymentInfoResponse from(PaymentTransaction payment) {
        if (payment == null || payment.getPaymentType() == null) {
            return null;
        }
        String type = payment.getPaymentType();
        if (type.startsWith("ATM_")) {
            if (payment.getVAccount() == null) {
                return null;
            }
            return new OrderPaymentInfoResponse("ATM", payment.getBankCode(), payment.getVAccount(),
                    null, payment.getPaymentExpireAt());
        }
        if (type.startsWith("CVS_")) {
            if (payment.getPaymentNo() == null) {
                return null;
            }
            return new OrderPaymentInfoResponse("CVS", null, null,
                    payment.getPaymentNo(), payment.getPaymentExpireAt());
        }
        return null;
    }
}
