package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.enums.order.InvoiceType;

/**
 * 訂單發票資訊，供「我的訂單」明細與付款結果頁顯示。
 * {@code carrier}／{@code taxId}／{@code donationCode} 依 {@code type} 只會其中一個非空
 * （個人載具則三者皆空）。訂單未帶發票資訊時整個物件為 {@code null}，前端顯示為「電子發票（個人）」。
 */
public record InvoiceInfoResponse(InvoiceType type, String carrier, String taxId, String donationCode) {

    public static InvoiceInfoResponse from(Order order) {
        if (order.getInvoiceType() == null) {
            return null;
        }
        return new InvoiceInfoResponse(order.getInvoiceType(), order.getInvoiceCarrier(),
                order.getInvoiceTaxId(), order.getInvoiceDonationCode());
    }
}
