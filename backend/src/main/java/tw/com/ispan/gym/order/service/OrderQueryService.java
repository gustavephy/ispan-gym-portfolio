package tw.com.ispan.gym.order.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.com.ispan.gym.common.exception.ResourceNotFoundException;
import tw.com.ispan.gym.order.dto.OrderDetailResponse;
import tw.com.ispan.gym.order.dto.OrderItemResponse;
import tw.com.ispan.gym.order.dto.OrderSummaryResponse;
import tw.com.ispan.gym.order.entity.Order;
import tw.com.ispan.gym.order.repository.OrderItemRepository;
import tw.com.ispan.gym.order.repository.OrderRepository;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;

    public OrderQueryService(OrderRepository orders, OrderItemRepository orderItems) {
        this.orders = orders;
        this.orderItems = orderItems;
    }

    public List<OrderSummaryResponse> findMine(Integer userId) {
        return orders.findAllByUserIdOrderByCreatedAtDesc(userId).stream().map(this::summary).toList();
    }

    public OrderDetailResponse findMineById(Integer userId, Long orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("?曆??唳?摰?閮"));
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(item -> new OrderItemResponse(item.getId(), item.getProduct().getId(),
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    private OrderSummaryResponse summary(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
