package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.member.MemberCoupon;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.enums.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 訂單明細。{@code title} 是給「我的訂單」列表／卡片當標題用的購買內容摘要
 * （訂閱＝方案名、單一品項＝品項名、多品項＝「第一項 等 N 項」），
 * {@code subscription} 只有訂閱訂單非空（見 {@link OrderSubscriptionResponse}）。
 *
 * <p>{@code subtotalAmount}／{@code discountAmount}／{@code totalAmount} 為金額三段拆解，
 * 結帳頁與付款結果頁用來顯示「商品小計 / 優惠折抵 / 應付金額」；{@code couponCode}／{@code couponName}
 * 於本筆訂單有使用優惠券時非空。{@code invoice} 於使用者選填發票資訊時非空（見 {@link InvoiceInfoResponse}）。
 */
public record OrderDetailResponse(
        Integer orderId,
        String merchantTradeNo,
        String title,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String couponCode,
        String couponName,
        OrderStatus status,
        LocalDateTime createdAt,
        List<OrderItemResponse> items,
        OrderSubscriptionResponse subscription,
        OrderPaymentInfoResponse paymentInfo,
        InvoiceInfoResponse invoice) {

    /**
     * 由訂單實體與已組好的品項／訂閱／繳費資訊組出明細回應，統一從 {@link Order} 取金額三段、
     * 優惠券與發票資訊。{@code OrderService}（建單/取消/出貨等當下，{@code paymentInfo} 傳 null）
     * 與 {@code OrderQueryService}（查詢，會帶 ATM/CVS 繳費資訊）共用，避免兩處建構參數漂移。
     */
    public static OrderDetailResponse of(Order order, List<OrderItemResponse> items,
            OrderSubscriptionResponse subscription, OrderPaymentInfoResponse paymentInfo) {
        MemberCoupon mc = order.getMemberCoupon();
        String couponCode = mc == null || mc.getCoupon() == null ? null : mc.getCoupon().getCode();
        String couponName = mc == null || mc.getCoupon() == null ? null : mc.getCoupon().getName();
        return new OrderDetailResponse(order.getOrderId(), order.getMerchantTradeNo(),
                title(subscription, items), order.getSubtotalAmount(), order.getDiscountAmount(),
                order.getTotalAmount(), couponCode, couponName, order.getStatus(), order.getCreatedAt(),
                items, subscription, paymentInfo, InvoiceInfoResponse.from(order));
    }

    /**
     * 購買內容摘要標題。優先用訂閱方案名；否則看品項——單一品項用品項名，
     * 多品項用「第一項 等 N 項」；兩者皆無（理論上不會發生）回 {@code null}，由前端退回顯示訂單編號。
     */
    public static String title(OrderSubscriptionResponse subscription, List<OrderItemResponse> items) {
        if (subscription != null) {
            return subscription.name();
        }
        if (items == null || items.isEmpty()) {
            return null;
        }
        String first = items.get(0).productNameAtBuy();
        return items.size() == 1 ? first : first + " 等 " + items.size() + " 項";
    }
}
