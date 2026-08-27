package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.enums.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 訂單明細。{@code title} 是給「我的訂單」列表／卡片當標題用的購買內容摘要
 * （訂閱＝方案名、單一品項＝品項名、多品項＝「第一項 等 N 項」），
 * {@code subscription} 只有訂閱訂單非空（見 {@link OrderSubscriptionResponse}）。
 */
public record OrderDetailResponse(
        Integer orderId,
        String merchantTradeNo,
        String title,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt,
        List<OrderItemResponse> items,
        OrderSubscriptionResponse subscription) {

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
