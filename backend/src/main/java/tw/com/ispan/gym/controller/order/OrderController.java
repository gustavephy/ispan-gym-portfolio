package tw.com.ispan.gym.controller.order;


import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderStatusHistoryResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.service.idempotency.IdempotencyService;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 所有建立訂單／取消訂單的端點都支援選填的 {@code Idempotency-Key} header：
 * 帶上時同一把金鑰（同一會員、同一操作）重送只會真正執行一次，見 {@link IdempotencyService}。
 * 不帶這個 header 行為與之前完全相同（相容舊呼叫）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderQueryService orderQueryService;
    private final OrderService orderService;
    private final EcpayService ecpayService;
    private final OrderRepository orders;
    private final CurrentUserProvider currentUser;
    private final IdempotencyService idempotency;

    public OrderController(OrderQueryService orderQueryService, OrderService orderService,
            EcpayService ecpayService, OrderRepository orders, CurrentUserProvider currentUser,
            IdempotencyService idempotency) {
        this.orderQueryService = orderQueryService;
        this.orderService = orderService;
        this.ecpayService = ecpayService;
        this.orders = orders;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
    }

    @GetMapping
    public List<OrderSummaryResponse> findMine() {
        return orderQueryService.findMine(currentUser.requireCurrentMemberId());
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse findMineById(@PathVariable Integer orderId) {
        return orderQueryService.findMineById(currentUser.requireCurrentMemberId(), orderId);
    }

    /** 訂單狀態時間軸（下單/付款/出貨/取消/退款等事件），僅限本人訂單 */
    @GetMapping("/{orderId}/history")
    public List<OrderStatusHistoryResponse> history(@PathVariable Integer orderId) {
        return orderQueryService.findHistory(currentUser.requireCurrentMemberId(), orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutFromCart(@Valid @RequestBody(required = false) CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer memberId = currentUser.requireCurrentMemberId();
        Integer orderId = idempotency.resolve(memberId, "CHECKOUT_CART", idempotencyKey, request,
                () -> orderService.createOrderFromCart(memberId, request).orderId());
        return orderQueryService.findMineById(memberId, orderId);
    }

    /**
     * 課程略過購物車直接結帳；一般商品請改用 {@link #checkoutFromCart}。
     */
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutDirect(@Valid @RequestBody DirectCheckoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer memberId = currentUser.requireCurrentMemberId();
        Integer orderId = idempotency.resolve(memberId, "CHECKOUT_DIRECT_COURSE", idempotencyKey, request,
                () -> orderService.createDirectOrder(memberId, request).orderId());
        return orderQueryService.findMineById(memberId, orderId);
    }

    /**
     * 會員訂閱略過購物車直接結帳；課程請改用 {@link #checkoutDirect}。
     */
    @PostMapping("/direct/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDetailResponse checkoutDirectSubscription(@Valid @RequestBody SubscriptionCheckoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer memberId = currentUser.requireCurrentMemberId();
        Integer orderId = idempotency.resolve(memberId, "CHECKOUT_SUBSCRIPTION", idempotencyKey, request,
                () -> orderService.createSubscriptionOrder(memberId, request).orderId());
        return orderQueryService.findMineById(memberId, orderId);
    }

    /**
     * 訂閱一步到位結帳：建立未付款訂閱訂單後緊接著組好 ECPay 表單欄位一併回傳，
     * 前端收到後直接組表單送出即可導向 ECPay 付款頁，不用像 {@link #checkoutDirectSubscription}
     * 那樣再另外呼叫一次 {@code PaymentController#checkout}。
     */
    @PostMapping("/direct/subscription/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionCheckoutResponse checkoutDirectSubscriptionWithEcpay(
            @Valid @RequestBody SubscriptionCheckoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer memberId = currentUser.requireCurrentMemberId();
        Integer orderId = idempotency.resolve(memberId, "CHECKOUT_SUBSCRIPTION_ECPAY", idempotencyKey, request,
                () -> orderService.createSubscriptionOrder(memberId, request).orderId());
        OrderDetailResponse detail = orderQueryService.findMineById(memberId, orderId);
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        Map<String, String> ecpayForm = ecpayService.buildCheckoutForm(order);
        return new SubscriptionCheckoutResponse(detail, ecpayForm);
    }

    /**
     * 未付款訂單直接取消並還原庫存/優惠券；已付款訂單改為建立一筆待處理退款，
     * 訂單狀態維持 PAID，實際退款由後台完成（見 AdminOrderController）。
     * operationName 帶上 orderId，避免同一把 Idempotency-Key 誤用在不同訂單上。
     */
    @PostMapping("/{orderId}/cancel")
    public OrderDetailResponse cancelOrder(@PathVariable Integer orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Integer memberId = currentUser.requireCurrentMemberId();
        Integer resolvedOrderId = idempotency.resolve(memberId, "CANCEL_ORDER:" + orderId, idempotencyKey, request,
                () -> orderService.cancelOrder(memberId, orderId, request).orderId());
        return orderQueryService.findMineById(memberId, resolvedOrderId);
    }
}

