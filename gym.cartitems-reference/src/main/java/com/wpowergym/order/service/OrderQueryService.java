package com.wpowergym.order.service;

import com.wpowergym.common.exception.ResourceNotFoundException;
import com.wpowergym.order.dto.*;
import com.wpowergym.order.entity.Order;
import com.wpowergym.order.repository.OrderItemRepository;
import com.wpowergym.order.repository.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;

    public OrderQueryService(OrderRepository orders, OrderItemRepository orderItems) {
        this.orders = orders; this.orderItems = orderItems;
    }

    public List<OrderSummaryResponse> findMine(Long userId) {
        return orders.findAllByUserIdOrderByCreatedAtDesc(userId).stream().map(this::summary).toList();
    }

    public OrderDetailResponse findMineById(Long userId, Long orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("訂單不存在"));
        List<OrderItemResponse> items = orderItems.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(item -> new OrderItemResponse(item.getId(), item.getProduct().getId(),
                        item.getProductNameAtBuy(), item.getPriceAtBuy(), item.getQuantity(),
                        item.getPriceAtBuy().multiply(java.math.BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt(), items);
    }

    private OrderSummaryResponse summary(Order order) {
        return new OrderSummaryResponse(order.getId(), order.getMerchantTradeNo(), order.getTotalAmount(),
                order.getStatus(), order.getCreatedAt());
    }
}
