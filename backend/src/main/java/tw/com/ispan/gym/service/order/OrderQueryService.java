package tw.com.ispan.gym.service.order;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.OrderStatusHistoryResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;

    public OrderQueryService(OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
    }

    public List<OrderSummaryResponse> findMine(Integer memberId) {
        return orders.findAllByMemberIdOrderByCreatedAtDesc(memberId).stream().map(this::summary).toList();
    }

    public OrderDetailResponse findMineById(Integer memberId, Integer orderId) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(item -> new OrderItemResponse(item.getOrderItemId(),
                        item.isCourseItem() ? null : item.getProduct().getProductId(),
                        item.isCourseItem() ? item.getCourse().getId() : null,
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    /** 訂單狀態時間軸，僅限本人訂單 */
    public List<OrderStatusHistoryResponse> findHistory(Integer memberId, Integer orderId) {
        orders.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        return orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(orderId).stream()
                .map(h -> new OrderStatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getChangeSource(),
                        h.getNote(), h.getChangedAt()))
                .toList();
    }

    private OrderSummaryResponse summary(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
