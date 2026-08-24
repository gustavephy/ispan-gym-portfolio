package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import tw.com.ispan.gym.dto.order.DirectCheckoutRequest;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.SubscriptionCheckoutRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.payment.PaymentRefund;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.SubscriptionRequiredException;
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
 * 純 Mockito 單元測試（沿用 CoachBookingServiceTest 的做法：專案無內嵌測試資料庫，
 * 不用 @SpringBootTest，直接 mock Repository）。
 *
 * 涵蓋兩條對稱的訂閱守衛規則（都複用同一支
 * {@code MemberSubscriptionRepository#findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc}）：
 * 「名下有有效訂閱 → 不能再訂閱」與「名下無有效訂閱 → 不能買私教課」。
 */
class OrderServiceSubscriptionTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer COURSE_ID = 10;
    private static final Integer PLAN_ID = 20;

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

    // === 共用測試資料 ========================================================

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

    private PrivateCourse course() {
        PrivateCourse course = new PrivateCourse();
        course.setId(COURSE_ID);
        course.setName("一對一體驗課");
        course.setPrice(BigDecimal.valueOf(2000));
        course.setSessionPerUnit(1);
        course.setStatus(CourseStatus.ACTIVE);
        return course;
    }

    private SubscriptionPlan plan() {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setSubscriptionPlanId(PLAN_ID);
        plan.setName("月費會員");
        plan.setPrice(BigDecimal.valueOf(999));
        plan.setDurationDays(30);
        plan.setStatus("ACTIVE");
        return plan;
    }

    private MemberSubscription activeSubscription() {
        MemberSubscription subscription = new MemberSubscription();
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setEndDate(LocalDateTime.now().plusDays(10));
        return subscription;
    }

    private void stubOrderPersistence() {
        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());
    }

    /** 已付款訂單，subscriptionPlan 依 isSubscription 決定是否標記為訂閱購買 */
    private Order paidOrder(boolean isSubscription) {
        Order order = new Order(member(), "GYM1", "ORD1", BigDecimal.valueOf(999), BigDecimal.ZERO,
                BigDecimal.valueOf(999), BigDecimal.valueOf(999), null, "王小明", "0912345678", null,
                "member@example.com");
        if (isSubscription) {
            order.setSubscriptionPlan(plan());
        }
        order.markPaid("Credit", "T1");
        return order;
    }

    private PaymentRefund pendingRefund(Order order) {
        PaymentTransaction payment = new PaymentTransaction(order, "ECPAY", order.getMerchantTradeNo(),
                order.getTotalAmount(), "TWD");
        return new PaymentRefund(payment, "RF1", order.getTotalAmount(), "會員申請退款");
    }

    // === createDirectOrder：需先訂閱才能買私教課 ==============================

    @Test
    void 買私教課_沒有有效訂閱時擋下() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course()));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                eq(MEMBER_ID), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(Optional.empty());

        DirectCheckoutRequest request = new DirectCheckoutRequest(COURSE_ID, 1, null);

        assertThatThrownBy(() -> service.createDirectOrder(MEMBER_ID, request))
                .isInstanceOf(SubscriptionRequiredException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void 買私教課_有有效訂閱時正常建立訂單() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course()));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                eq(MEMBER_ID), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(Optional.of(activeSubscription()));
        stubOrderPersistence();

        OrderDetailResponse response = service.createDirectOrder(MEMBER_ID,
                new DirectCheckoutRequest(COURSE_ID, 1, null));

        assertThat(response).isNotNull();
        verify(orders).save(any(Order.class));
    }

    // === createSubscriptionOrder：不允許提前續約（既有邏輯，先前缺測試）===========

    @Test
    void 訂閱_名下已有未到期訂閱時擋下() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(subscriptionPlans.findById(PLAN_ID)).thenReturn(Optional.of(plan()));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                eq(MEMBER_ID), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(Optional.of(activeSubscription()));

        SubscriptionCheckoutRequest request = new SubscriptionCheckoutRequest(PLAN_ID, null);

        assertThatThrownBy(() -> service.createSubscriptionOrder(MEMBER_ID, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("尚有未到期的訂閱");
        verify(orders, never()).save(any());
    }

    @Test
    void 訂閱_名下無有效訂閱時正常建立訂單() {
        when(users.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(subscriptionPlans.findById(PLAN_ID)).thenReturn(Optional.of(plan()));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(
                eq(MEMBER_ID), any(), eq(SubscriptionStatus.ACTIVE))).thenReturn(Optional.empty());
        stubOrderPersistence();

        OrderDetailResponse response = service.createSubscriptionOrder(MEMBER_ID,
                new SubscriptionCheckoutRequest(PLAN_ID, null));

        assertThat(response).isNotNull();
        verify(orders).save(any(Order.class));
    }

    // === completeRefund：退款需連動取消訂閱 ===================================

    @Test
    void 退款_訂閱訂單完成退款時連動取消訂閱() {
        Order order = paidOrder(true);
        PaymentRefund refund = pendingRefund(order);
        MemberSubscription subscription = activeSubscription();
        when(paymentRefunds.findById(1)).thenReturn(Optional.of(refund));
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());
        when(subscriptions.findByOrderId(any())).thenReturn(Optional.of(subscription));

        service.completeRefund(1);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refund.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void 退款_一般商品訂單完成退款時不觸碰訂閱查詢() {
        Order order = paidOrder(false);
        PaymentRefund refund = pendingRefund(order);
        when(paymentRefunds.findById(2)).thenReturn(Optional.of(refund));
        when(orderItems.findAllByOrderIdOrderByIdAsc(any())).thenReturn(List.of());

        service.completeRefund(2);

        verify(subscriptions, never()).findByOrderId(any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }
}
