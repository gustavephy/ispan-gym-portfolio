package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import java.math.BigDecimal;

/** productId 與 privateCourseId 恰好一個非空，對應 order_item 的雙 FK */
public record OrderItemResponse(
        Integer orderItemId,
        Integer productId,
        Integer privateCourseId,
        String productNameAtBuy,
        BigDecimal priceAtBuy,
        Integer quantity,
        BigDecimal lineAmount) {}

