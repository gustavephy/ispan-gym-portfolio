package tw.com.ispan.gym.service.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.OrderStatusHistoryResponse;
import tw.com.ispan.gym.dto.order.OrderSubscriptionResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
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
        List<Order> mine = orders.findAllByMemberIdOrderByCreatedAtDesc(memberId);
        // 一次撈齊所有訂單的明細再依 order id 分組，避免逐筆查明細算標題的 N+1
        Map<Integer, List<OrderItem>> itemsByOrder = orderItems.findAllByOrderIdInOrderByOrderIdAscIdAsc(
                mine.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        return mine.stream().map(order -> summary(order, itemsByOrder.getOrDefault(order.getId(), List.of()))).toList();
    }

    public OrderDetailResponse findMineById(Integer memberId, Integer orderId) {
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(OrderItemResponse::from)
                .toList();
        OrderSubscriptionResponse subscription = OrderSubscriptionResponse.from(order.getSubscriptionPlan());
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(),
                OrderDetailResponse.title(subscription, items), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items, subscription);
    }

    /** 訂單狀態時間軸，僅限本人訂單 */
    public List<OrderStatusHistoryResponse> findHistory(Integer memberId, Integer orderId) {
        orders.findByIdAndMemberId(orderId, memberId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        return orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(orderId).stream()
                .map(h -> new OrderStatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getChangeSource(),
                        h.getNote(), h.getChangedAt()))
                .toList();
    }

    private OrderSummaryResponse summary(Order order, List<OrderItem> items) {
        OrderSubscriptionResponse subscription = OrderSubscriptionResponse.from(order.getSubscriptionPlan());
        String title = OrderDetailResponse.title(subscription, items.stream().map(OrderItemResponse::from).toList());
        return new OrderSummaryResponse(order.getId(), order.getMerchantTradeNo(), title, order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
