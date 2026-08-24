package tw.com.ispan.gym.dto.order;

import tw.com.ispan.gym.enums.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdminOrderDetailResponse(
        Integer orderId,
        String orderNo,
        String merchantTradeNo,
        String tradeNo,
        Integer memberId,
        String memberName,
        String memberEmail,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        OrderStatus status,
        String paymentMethod,
        LocalDateTime paidAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String receiverEmail,
        LocalDateTime createdAt,
        List<OrderItemResponse> items,
        List<OrderStatusHistoryResponse> history) {}
