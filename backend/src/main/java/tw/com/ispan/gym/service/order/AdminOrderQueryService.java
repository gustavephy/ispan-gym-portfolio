package tw.com.ispan.gym.service.order;

import tw.com.ispan.gym.dto.order.AdminOrderDetailResponse;
import tw.com.ispan.gym.dto.order.AdminOrderSummaryResponse;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.OrderStatusHistoryResponse;
import tw.com.ispan.gym.dto.order.OrderSubscriptionResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 後台訂單查詢：搜尋列表、單筆詳情（含明細與狀態時間軸）。純讀取，不動訂單狀態，見 {@link OrderService} 的 shipOrder/completeOrder。 */
@Service
@Transactional(readOnly = true)
public class AdminOrderQueryService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository orderStatusHistories;

    public AdminOrderQueryService(OrderRepository orders, OrderItemRepository orderItems,
            OrderStatusHistoryRepository orderStatusHistories) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.orderStatusHistories = orderStatusHistories;
    }

    public Page<AdminOrderSummaryResponse> search(OrderStatus status, Integer memberId, LocalDateTime from,
            LocalDateTime to, String keyword, Pageable pageable) {
        String likeKeyword = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim().toLowerCase() + "%";
        Page<Order> page = orders.search(status, memberId, from, to, likeKeyword, pageable);
        // 這一頁的訂單明細一次撈齊再依 order id 分組，算購買內容標題時不逐筆查
        Map<Integer, List<OrderItem>> itemsByOrder = orderItems.findAllByOrderIdInOrderByOrderIdAscIdAsc(
                page.getContent().stream().map(Order::getId).toList()).stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        return page.map(order -> summary(order, itemsByOrder.getOrDefault(order.getId(), List.of())));
    }

    public AdminOrderDetailResponse findById(Integer orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));

        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .sorted(Comparator.comparing(item -> item.getId()))
                .map(OrderItemResponse::from)
                .toList();

        List<OrderStatusHistoryResponse> history = orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(orderId)
                .stream()
                .map(h -> new OrderStatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getChangeSource(),
                        h.getNote(), h.getChangedAt()))
                .toList();

        OrderSubscriptionResponse subscription = OrderSubscriptionResponse.from(order.getSubscriptionPlan());

        return new AdminOrderDetailResponse(order.getId(), order.getOrderNo(), order.getMerchantTradeNo(),
                order.getTradeNo(), OrderDetailResponse.title(subscription, items), order.getMember().getId(),
                memberName(order), order.getMember().getAccount().getEmail(),
                order.getSubtotalAmount(), order.getDiscountAmount(), order.getTotalAmount(), order.getStatus(),
                order.getPaymentMethod(), order.getPaidAt(), order.getCancelledAt(), order.getCancelReason(),
                order.getReceiverName(), order.getReceiverPhone(), order.getReceiverAddress(), order.getReceiverEmail(),
                order.getCreatedAt(), items, subscription, history);
    }

    private AdminOrderSummaryResponse summary(Order order, List<OrderItem> items) {
        OrderSubscriptionResponse subscription = OrderSubscriptionResponse.from(order.getSubscriptionPlan());
        String title = OrderDetailResponse.title(subscription, items.stream().map(OrderItemResponse::from).toList());
        return new AdminOrderSummaryResponse(order.getId(), order.getOrderNo(), order.getMerchantTradeNo(), title,
                order.getMember().getId(), memberName(order), order.getMember().getAccount().getEmail(),
                order.getTotalAmount(), order.getStatus(), order.getPaymentMethod(), order.getCreatedAt(),
                order.getPaidAt());
    }

    private String memberName(Order order) {
        return order.getMember().getFirstName() + order.getMember().getLastName();
    }
}
