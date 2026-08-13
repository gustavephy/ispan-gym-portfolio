package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.entity.order.Order;

import java.math.BigDecimal;

/** productId 與 courseId 恰好一個非空，對應 order_item 的雙 FK */
public record OrderItemResponse(
        Long orderItemId,
        Integer productId,
        Integer courseId,
        String productNameAtBuy,
        BigDecimal priceAtBuy,
        Long quantity,
        BigDecimal lineAmount) {}

