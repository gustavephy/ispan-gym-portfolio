package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.repository.cart.CartItemRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;
import tw.com.ispan.gym.repository.member.MemberCouponRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;
import tw.com.ispan.gym.service.payment.EcpayService;

/**
 * 「金流接縫」契約級測試：用同一組 mock 持久層把真牌 {@link OrderService} 與
 * {@link EcpayService} 串起來，驗證「購物車結帳 → 建單扣庫存 → 綠界付款成功通知 → 訂單轉 PAID」
 * 這一整條的交接。
 *
 * <p><b>注意</b>：這不是連真實資料庫的整合測試——專案沒有內嵌測試資料庫（見既有
 * *ServiceTest 的類別註解），也用了 SQL Server 專屬語法（filtered index、SchemaFixupRunner 原生 DDL），
 * 不適合塞 H2 / Testcontainers。這裡只保證兩個 service 對彼此的呼叫契約沒有漂移，
 * 真實的交易 / lazy loading / flush 行為仍需靠實際資料庫手動驗證。
 */
class CheckoutMoneyPathTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer PRODUCT_ID = 100;
    private static final String HASH_KEY = "5294y06JbISpM5x9";
    private static final String HASH_IV = "v77hoKGq4kWxNNIS";

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PrivateCourseRepository courses = mock(PrivateCourseRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final MemberCouponRepository memberCoupons = mock(MemberCouponRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistories = mock(OrderStatusHistoryRepository.class);
    private final SubscriptionPlanRepository subscriptionPlans = mock(SubscriptionPlanRepository.class);
    private final MemberSubscriptionRepository subscriptions = mock(MemberSubscriptionRepository.class);
    private final MemberCourseQuotaRepository courseQuotas = mock(MemberCourseQuotaRepository.class);
    private final PaymentTransactionRepository paymentTransactions = mock(PaymentTransactionRepository.class);
    private final PaymentRefundRepository paymentRefunds = mock(PaymentRefundRepository.class);
    private final PaymentWebhookRepository paymentWebhooks = mock(PaymentWebhookRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final OrderService orderService = new OrderService(cartItems, products, courses, members, coupons,
            memberCoupons, orders, orderItems, orderStatusHistories, subscriptionPlans, subscriptions, courseQuotas,
            paymentTransactions, paymentRefunds, events);

    private final RestTemplate restTemplate = mock(RestTemplate.class);

    private final EcpayService ecpayService = new EcpayService("2000132", HASH_KEY, HASH_IV,
            "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
            "https://example.com/api/payments/ecpay/notify",
            "https://example.com/api/payments/ecpay/payment-info",
            "https://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5",
            "https://example.com/order-done", true,
            orders, orderItems, orderStatusHistories, paymentTransactions, paymentWebhooks, orderService,
            restTemplate);

    private Member member() {
        Account account = new Account();
        account.setEmail("buyer@example.com");
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setFirstName("小明");
        member.setLastName("王");
        member.setPhone("0912345678");
        member.setAccount(account);
        return member;
    }

    private Product product() {
        Product p = new Product();
        p.setProductId(PRODUCT_ID);
        p.setName("乳清蛋白");
        p.setPrice(new BigDecimal("500"));
        p.setStock(10);
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }

    @Test
    void 購物車結帳到綠界付款成功一整條() {
        Product product = product();
        Member member = member();
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID))
                .thenReturn(List.of(new CartItem(member, product, 2)));
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(products.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(orders.existsByMerchantTradeNo(any())).thenReturn(false);
        when(orders.existsByOrderNo(any())).thenReturn(false);
        when(orderStatusHistories.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
        when(orders.save(savedOrder.capture())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 900);
            return o;
        });
        ArgumentCaptor<OrderItem> savedItem = ArgumentCaptor.forClass(OrderItem.class);
        when(orderItems.save(savedItem.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());

        // === 步驟 1：購物車結帳 ===
        orderService.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(null, null, null, "台北市中正區測試路 1 號"));

        Order order = savedOrder.getValue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.UNPAID);
        assertThat(order.getMerchantTradeNo()).startsWith("GYM");
        assertThat(product.getStock()).isEqualTo(8); // 10 - 2
        verify(cartItems).deleteAllByMemberId(MEMBER_ID);

        // === 步驟 2：綠界付款成功通知 ===
        when(orders.findByMerchantTradeNo(order.getMerchantTradeNo())).thenReturn(Optional.of(order));
        when(paymentTransactions.findByMerchantTradeNo(order.getMerchantTradeNo())).thenReturn(Optional.empty());
        when(paymentTransactions.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentWebhooks.save(any(PaymentWebhook.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentWebhooks.existsByProviderAndEventKey(any(), any())).thenReturn(false);
        // fulfillPaidOrder 會再查一次明細（純商品訂單無課程/訂閱，等於 no-op）
        when(orderItems.findAllByOrderIdOrderByIdAsc(900)).thenReturn(List.of(savedItem.getValue()));

        ecpayService.handleNotify(signedNotify(order.getMerchantTradeNo(), "1000"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(paymentTransactions).save(any(PaymentTransaction.class));
    }

    /** 綠界通知的最小 payload，簽章用 EcpayService 內部同一套演算法補上（等同綠界簽出來的結果） */
    private Map<String, String> signedNotify(String merchantTradeNo, String tradeAmt) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID", "2000132");
        params.put("MerchantTradeNo", merchantTradeNo);
        params.put("TradeNo", "2604161200001234");
        params.put("RtnCode", "1");
        params.put("RtnMsg", "Succeeded");
        params.put("PaymentType", "Credit_CreditCard");
        params.put("TradeAmt", tradeAmt);
        params.put("CheckMacValue",
                (String) ReflectionTestUtils.invokeMethod(ecpayService, "generateCheckMacValue", params));
        return params;
    }
}
