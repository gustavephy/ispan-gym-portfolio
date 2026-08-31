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
import tw.com.ispan.gym.exception.OrderRateLimitedException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.service.idempotency.IdempotencyService;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    private final InMemoryRateLimiter rateLimiter;
    private final int rateLimitMax;
    private final Duration rateLimitWindow;

    public OrderController(OrderQueryService orderQueryService, OrderService orderService,
            EcpayService ecpayService, OrderRepository orders, CurrentUserProvider currentUser,
            IdempotencyService idempotency, InMemoryRateLimiter rateLimiter,
            @Value("${app.order.rate-limit.max:10}") int rateLimitMax,
            @Value("${app.order.rate-limit.window:PT1M}") Duration rateLimitWindow) {
        this.orderQueryService = orderQueryService;
        this.orderService = orderService;
        this.ecpayService = ecpayService;
        this.orders = orders;
        this.currentUser = currentUser;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.rateLimitMax = rateLimitMax;
        this.rateLimitWindow = rateLimitWindow;
    }

    /** 建單前的節流：擋「不同內容的訂單短時間狂建」，重送由 Idempotency-Key 另外處理 */
    private void guardOrderRateLimit(Integer memberId) {
        if (!rateLimiter.tryAcquire("order-create:" + memberId, rateLimitMax, rateLimitWindow)) {
            throw new OrderRateLimitedException();
        }
    }

    /**
     * 查詢付款結果前的節流：沿用跟建單相同的額度，擋使用者連續點擊查詢按鈕——
     * 綠界的 QueryTradeInfo 本身也有頻率限制，這裡先擋一層避免我方帳號被對方判定濫用。
     */
    private void guardSyncPaymentRateLimit(Integer memberId) {
        if (!rateLimiter.tryAcquire("sync-payment:" + memberId, rateLimitMax, rateLimitWindow)) {
            throw new OrderRateLimitedException();
        }
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
        guardOrderRateLimit(memberId);
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
        guardOrderRateLimit(memberId);
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
        guardOrderRateLimit(memberId);
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
        guardOrderRateLimit(memberId);
        Integer orderId = idempotency.resolve(memberId, "CHECKOUT_SUBSCRIPTION_ECPAY", idempotencyKey, request,
                () -> orderService.createSubscriptionOrder(memberId, request).orderId());
        OrderDetailResponse detail = orderQueryService.findMineById(memberId, orderId);
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        Map<String, String> ecpayForm = ecpayService.buildCheckoutForm(order);
        // buildCheckoutForm 會換一組新的 MerchantTradeNo（送給綠界的就是新號），必須明確存檔。
        // 這裡不能依賴 dirty checking 隱式寫回：本端點前面先經過 findMineById 的唯讀交易，
        // 實測換號後的新號寫不進資料庫（訂單留在舊號＋attempt=0），之後所有付款結果查詢
        // 都拿舊號去問綠界、永遠「查無交易」，訂單就卡在待付款（demo1 的 2089 實際發生）。
        orders.save(order);
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

    /**
     * 主動向綠界查詢這筆訂單的真實付款結果，取代等待 webhook 主動通知——本機沒有對外 URL
     * 收不到 return-url／payment-info-url 的呼叫時，改用這支端點讓前端在使用者從綠界返回、
     * 或按下「查詢付款結果」按鈕時觸發一次，見 {@link EcpayService#syncPaymentStatus}。
     * 只有本人的訂單能查；已經有結果（非 UNPAID）的訂單直接回目前狀態，不重複查詢。
     */
    @PostMapping("/{orderId}/sync-payment")
    public OrderDetailResponse syncPayment(@PathVariable Integer orderId) {
        Integer memberId = currentUser.requireCurrentMemberId();
        guardSyncPaymentRateLimit(memberId);
        Order order = orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        ecpayService.syncPaymentStatus(order);
        return orderQueryService.findMineById(memberId, orderId);
    }
}

