package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.enums.order.InvoiceType;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 購物車結帳輸入。發票欄位皆選填：{@code invoiceType} 為 null 時視同「個人（會員載具）」。
 * 統編／捐贈碼可用 {@code @Pattern} 直接驗；手機條碼載具格式與 type 相關，
 * 由 {@link tw.com.ispan.gym.service.order.OrderService} 的 {@code assertInvoiceValid} 檢查。
 */
public record CreateOrderRequest(
        @Size(max = 30) String couponCode,
        @Size(max = 50) String receiverName,
        @Size(max = 20) String receiverPhone,
        @Size(max = 200) String receiverAddress,
        InvoiceType invoiceType,
        @Size(max = 64) String invoiceCarrier,
        @Pattern(regexp = "^\\d{8}$", message = "統一編號需為 8 碼數字") String invoiceTaxId,
        @Pattern(regexp = "^\\d{2,7}$", message = "愛心捐贈碼需為 2~7 碼數字") String invoiceDonationCode) {

    /** 相容既有呼叫端／測試：不帶發票資訊的舊四參數建構子。 */
    public CreateOrderRequest(String couponCode, String receiverName, String receiverPhone,
            String receiverAddress) {
        this(couponCode, receiverName, receiverPhone, receiverAddress, null, null, null, null);
    }
}
