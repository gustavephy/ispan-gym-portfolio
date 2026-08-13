package tw.com.ispan.gym.controller;


import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
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
        return orderQueryService.findMine(currentUser.requireCurrentMemberId());
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse findMineById(@PathVariable Long orderId) {
        return orderQueryService.findMineById(currentUser.requireCurrentMemberId(), orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutFromCart(@Valid @RequestBody(required = false) CreateOrderRequest request) {
        return orderService.createOrderFromCart(currentUser.requireCurrentMemberId(), request);
    }

    /**
     * 課程略過購物車直接結帳；一般商品請改用 {@link #checkoutFromCart}。
     */
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutDirect(@Valid @RequestBody DirectCheckoutRequest request) {
        return orderService.createDirectOrder(currentUser.requireCurrentMemberId(), request);
    }

    /**
     * 會員訂閱略過購物車直接結帳；課程請改用 {@link #checkoutDirect}。
     */
    @PostMapping("/direct/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutDirectSubscription(@Valid @RequestBody SubscriptionCheckoutRequest request) {
        return orderService.createSubscriptionOrder(currentUser.requireCurrentMemberId(), request);
    }

    /**
     * 未付款訂單直接取消並還原庫存/優惠券；已付款訂單改為建立一筆待處理退款，
     * 訂單狀態維持 PAID，實際退款由後台完成（見 AdminOrderController）。
     */
    @PostMapping("/{orderId}/cancel")
    public OrderDetailResponse cancelOrder(@PathVariable Long orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelOrder(currentUser.requireCurrentMemberId(), orderId, request);
    }
}

