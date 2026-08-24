package tw.com.ispan.gym.service.order;

import tw.com.ispan.gym.dto.order.AdminOrderDetailResponse;
import tw.com.ispan.gym.dto.order.AdminOrderSummaryResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.OrderStatusHistoryResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

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
        return orders.search(status, memberId, from, to, likeKeyword, pageable).map(this::summary);
    }

    public AdminOrderDetailResponse findById(Integer orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));

        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .sorted(Comparator.comparing(item -> item.getId()))
                .map(item -> new OrderItemResponse(item.getId(),
                        item.isCourseItem() ? null : item.getProduct().getProductId(),
                        item.isCourseItem() ? item.getCourse().getId() : null,
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();

        List<OrderStatusHistoryResponse> history = orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(orderId)
                .stream()
                .map(h -> new OrderStatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getChangeSource(),
                        h.getNote(), h.getChangedAt()))
                .toList();

        return new AdminOrderDetailResponse(order.getId(), order.getOrderNo(), order.getMerchantTradeNo(),
                order.getTradeNo(), order.getMember().getId(), memberName(order), order.getMember().getAccount().getEmail(),
                order.getSubtotalAmount(), order.getDiscountAmount(), order.getTotalAmount(), order.getStatus(),
                order.getPaymentMethod(), order.getPaidAt(), order.getCancelledAt(), order.getCancelReason(),
                order.getReceiverName(), order.getReceiverPhone(), order.getReceiverAddress(), order.getReceiverEmail(),
                order.getCreatedAt(), items, history);
    }

    private AdminOrderSummaryResponse summary(Order order) {
        return new AdminOrderSummaryResponse(order.getId(), order.getOrderNo(), order.getMerchantTradeNo(),
                order.getMember().getId(), memberName(order), order.getMember().getAccount().getEmail(),
                order.getTotalAmount(), order.getStatus(), order.getPaymentMethod(), order.getCreatedAt(),
                order.getPaidAt());
    }

    private String memberName(Order order) {
        return order.getMember().getFirstName() + order.getMember().getLastName();
    }
}
