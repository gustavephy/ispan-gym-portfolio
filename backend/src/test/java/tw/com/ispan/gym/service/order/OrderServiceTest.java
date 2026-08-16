package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import tw.com.ispan.gym.dto.order.CancelOrderRequest;
import tw.com.ispan.gym.dto.order.CreateOrderRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.cart.CartItem;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.InsufficientStockException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
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
import tw.com.ispan.gym.repository.product.ProductRepository;
import tw.com.ispan.gym.repository.subscription.SubscriptionPlanRepository;

/**
 * 純 Mockito 單元測試（沿用 CoachBookingServiceTest 的做法）。
 * 涵蓋購物車結帳（{@link OrderService#createOrderFromCart}）與取消/退款申請
 * （{@link OrderService#cancelOrder}）；訂閱相關流程見 {@link OrderServiceSubscriptionTest}。
 */
class OrderServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer PRODUCT_ID = 100;
    private static final Long ORDER_ID = 900L;

    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PrivateCourseRepository courses = mock(PrivateCourseRepository.class);
    private final MemberRepository users = mock(MemberRepository.class);
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
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final OrderService service = new OrderService(cartItems, products, courses, users, coupons,
            memberCoupons, orders, orderItems, orderStatusHistories, subscriptionPlans, subscriptions, courseQuotas,
            paymentTransactions, paymentRefunds, events);

    private Member member() {
        Account account = new Account();
        account.setEmail("member@example.com");
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setFirstName("小明");
        member.setLastName("王");
        member.setPhone("0912345678");
        member.setAccount(account);
        return member;
    }

    private Product product(int stock) {
        Product product = new Product();
        product.setProductId(PRODUCT_ID);
        product.setName("乳清蛋白");
        product.setPrice(BigDecimal.valueOf(500));
        product.setStock(stock);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private void stubOrderAndItemPersistence() {
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());
    }

    // === createOrderFromCart ===================================================

    @Test
    void 購物車結帳_購物車是空的時擋下() {
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("購物車是空的");
        verify(orders, never()).save(any());
    }

    @Test
    void 購物車結帳_成功建立訂單並扣庫存清空購物車() {
        Product product = product(10);
        CartItem cartItem = new CartItem(member(), product, 2L);
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(cartItem));
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(products.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(product));
        stubOrderAndItemPersistence();

        OrderDetailResponse response = service.createOrderFromCart(MEMBER_ID,
                new CreateOrderRequest(null, null, null, null));

        assertThat(response).isNotNull();
        assertThat(product.getStock()).isEqualTo(8);
        verify(cartItems).deleteAllByMemberId(MEMBER_ID);
        verify(orders).save(any(Order.class));
    }

    @Test
    void 購物車結帳_庫存不足時擋下且不清空購物車() {
        Product product = product(1);
        CartItem cartItem = new CartItem(member(), product, 5L);
        when(cartItems.findAllByMemberIdOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(cartItem));
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(products.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.createOrderFromCart(MEMBER_ID, null))
                .isInstanceOf(InsufficientStockException.class);
        verify(orders, never()).save(any());
        verify(cartItems, never()).deleteAllByMemberId(any());
    }

    // === cancelOrder ============================================================

    private Order unpaidOrderWithItem(Product product) {
        Order order = new Order(member(), "GYM1", "ORD1", BigDecimal.valueOf(500), BigDecimal.ZERO,
                BigDecimal.valueOf(500), BigDecimal.valueOf(500), null, "王小明", "0912345678", null,
                "member@example.com");
        when(orderItems.findAllByOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of(new tw.com.ispan.gym.entity.order.OrderItem(order, product, product.getName(),
                        product.getPrice(), 2L)));
        return order;
    }

    @Test
    void 取消訂單_未付款時直接取消並還原庫存() {
        Product product = product(3);
        Order order = unpaidOrderWithItem(product);
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(products.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(product));

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, new CancelOrderRequest("改變心意"));

        assertThat(response).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getStock()).isEqualTo(5);
        verify(paymentRefunds, never()).save(any());
    }

    @Test
    void 取消訂單_已付款時轉為待處理退款且訂單維持PAID() {
        Order order = unpaidOrderWithItem(product(3));
        order.markPaid("Credit", "T1");
        PaymentTransaction payment = new PaymentTransaction(order, "ECPAY", "GYM1", order.getTotalAmount(), "TWD");
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));
        when(paymentTransactions.findByOrderId(order.getId())).thenReturn(Optional.of(payment));
        when(paymentRefunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderDetailResponse response = service.cancelOrder(MEMBER_ID, ORDER_ID, new CancelOrderRequest("商品有瑕疵"));

        assertThat(response).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(paymentRefunds).save(any());
        verify(products, never()).findByIdForUpdate(any());
    }

    @Test
    void 取消訂單_查無訂單回404() {
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(MEMBER_ID, ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 取消訂單_已取消的訂單不可再取消() {
        Order order = unpaidOrderWithItem(product(3));
        order.cancel("第一次取消");
        when(orders.findByIdAndMemberId(ORDER_ID, MEMBER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(MEMBER_ID, ORDER_ID, null))
                .isInstanceOf(BusinessRuleException.class);
    }
}
