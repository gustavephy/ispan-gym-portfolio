package tw.com.ispan.gym.controller.order;


import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutResponse;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.service.order.OrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;
import tw.com.ispan.gym.service.payment.EcpayService;
import tw.com.ispan.gym.security.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderQueryService orderQueryService;
    private final OrderService orderService;
    private final EcpayService ecpayService;
    private final OrderRepository orders;
    private final CurrentUserProvider currentUser;

    public OrderController(OrderQueryService orderQueryService, OrderService orderService,
            EcpayService ecpayService, OrderRepository orders, CurrentUserProvider currentUser) {
        this.orderQueryService = orderQueryService;
        this.orderService = orderService;
        this.ecpayService = ecpayService;
        this.orders = orders;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<OrderSummaryResponse> findMine() {
        return orderQueryService.findMine(currentUser.requireCurrentMemberId());
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse findMineById(@PathVariable Integer orderId) {
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
     * 訂閱一步到位結帳：建立未付款訂閱訂單後緊接著組好 ECPay 表單欄位一併回傳，
     * 前端收到後直接組表單送出即可導向 ECPay 付款頁，不用像 {@link #checkoutDirectSubscription}
     * 那樣再另外呼叫一次 {@code PaymentController#checkout}。
     */
    @PostMapping("/direct/subscription/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionCheckoutResponse checkoutDirectSubscriptionWithEcpay(
            @Valid @RequestBody SubscriptionCheckoutRequest request) {
        Integer memberId = currentUser.requireCurrentMemberId();
        OrderDetailResponse detail = orderService.createSubscriptionOrder(memberId, request);
        Order order = orders.findByIdAndMemberId(detail.orderId(), memberId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到訂單資料"));
        Map<String, String> ecpayForm = ecpayService.buildCheckoutForm(order);
        return new SubscriptionCheckoutResponse(detail, ecpayForm);
    }

    /**
     * 未付款訂單直接取消並還原庫存/優惠券；已付款訂單改為建立一筆待處理退款，
     * 訂單狀態維持 PAID，實際退款由後台完成（見 AdminOrderController）。
     */
    @PostMapping("/{orderId}/cancel")
    public OrderDetailResponse cancelOrder(@PathVariable Integer orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelOrder(currentUser.requireCurrentMemberId(), orderId, request);
    }
}

