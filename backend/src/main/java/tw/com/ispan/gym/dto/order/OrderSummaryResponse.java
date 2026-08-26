package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.enums.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 訂單列表項目。{@code title} 為購買內容摘要（訂閱方案名／品項名／「第一項 等 N 項」），
 * 「我的訂單」用它當卡片標題，滑鼠移上去才顯示 {@code merchantTradeNo} 原始訂單編號。
 * 計算規則見 {@link OrderDetailResponse#title}。
 */
public record OrderSummaryResponse(
        Integer orderId,
        String merchantTradeNo,
        String title,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt) {}
