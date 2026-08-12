package tw.com.ispan.gym.controller;

import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.service.OrderQueryService;
import tw.com.ispan.gym.service.OrderService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderQueryService orderQueryService;
    private final OrderService orderService;
    private final CurrentUserProvider currentUser;

    public OrderController(OrderQueryService orderQueryService, OrderService orderService,
            CurrentUserProvider currentUser) {
        this.orderQueryService = orderQueryService;
        this.orderService = orderService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<OrderSummaryResponse> findMine() {
        return orderQueryService.findMine(currentUser.requireCurrentUserId());
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse findMineById(@PathVariable Long orderId) {
        return orderQueryService.findMineById(currentUser.requireCurrentUserId(), orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutFromCart(@Valid @RequestBody(required = false) CreateOrderRequest request) {
        return orderService.createOrderFromCart(currentUser.requireCurrentUserId(), request);
    }

    /**
     * 課程略過購物車直接結帳；一般商品請改用 {@link #checkoutFromCart}。
     */
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutDirect(@Valid @RequestBody DirectCheckoutRequest request) {
        return orderService.createDirectOrder(currentUser.requireCurrentUserId(), request);
    }

    /**
     * 目前僅支援取消未付款訂單；已付款訂單的取消需搭配退款流程，待金流串接後再開放。
     */
    @PostMapping("/{orderId}/cancel")
    public OrderDetailResponse cancelOrder(@PathVariable Long orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelOrder(currentUser.requireCurrentUserId(), orderId, request);
    }
}

