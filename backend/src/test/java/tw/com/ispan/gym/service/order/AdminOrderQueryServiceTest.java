package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.order.AdminOrderDetailResponse;
import tw.com.ispan.gym.dto.order.AdminOrderSummaryResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;

/** 純 Mockito 單元測試，搭配後台訂單搜尋頁與退款/出貨聯動使用。 */
class AdminOrderQueryServiceTest {

    private static final Integer ORDER_ID = 900;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistories = mock(OrderStatusHistoryRepository.class);

    private final AdminOrderQueryService service =
            new AdminOrderQueryService(orders, orderItems, orderStatusHistories);

    private Order order() {
        Account account = new Account();
        account.setEmail("buyer@example.com");
        Member member = new Member();
        member.setId(1);
        member.setFirstName("小明");
        member.setLastName("王");
        member.setPhone("0912345678");
        member.setAccount(account);

        Order order = new Order(member, "GYM1", "ORD1", new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"), null, "王小明", "0912345678", "地址",
                "buyer@example.com");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    // === search() ==============================================================

    @Test
    void 搜尋時關鍵字轉小寫並包上萬用字元() {
        when(orders.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        Pageable pageable = PageRequest.of(0, 20);

        service.search(null, null, null, null, "GYM-Test", pageable);

        verify(orders).search(isNull(), isNull(), isNull(), isNull(), eq("%gym-test%"), eq(pageable));
    }

    @Test
    void 搜尋關鍵字為空白字串時視為不篩選() {
        when(orders.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        Pageable pageable = PageRequest.of(0, 20);

        service.search(OrderStatus.PAID, 5, null, null, "   ", pageable);

        verify(orders).search(eq(OrderStatus.PAID), eq(5), isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    void 搜尋結果轉為摘要含會員姓名與信箱() {
        Order order = order();
        when(orders.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(order)));

        Page<AdminOrderSummaryResponse> result =
                service.search(null, null, null, null, null, PageRequest.of(0, 20));

        AdminOrderSummaryResponse summary = result.getContent().get(0);
        assertThat(summary.orderId()).isEqualTo(ORDER_ID);
        // memberName 沿用 OrderService 建單時同一套 firstName+lastName 直接串接的慣例
        assertThat(summary.memberName()).isEqualTo("小明王");
        assertThat(summary.memberEmail()).isEqualTo("buyer@example.com");
        assertThat(summary.status()).isEqualTo(OrderStatus.UNPAID);
    }

    @Test
    void 搜尋摘要帶出購買內容標題() {
        Order productOrder = order();
        Product product = new Product();
        product.setProductId(10);
        product.setName("測試商品");
        product.setPrice(new BigDecimal("500"));
        OrderItem item = new OrderItem(productOrder, product, "測試商品", new BigDecimal("500"), 1);
        ReflectionTestUtils.setField(item, "id", 1);
        when(orders.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(productOrder)));
        when(orderItems.findAllByOrderIdInOrderByOrderIdAscIdAsc(any())).thenReturn(List.of(item));

        AdminOrderSummaryResponse summary =
                service.search(null, null, null, null, null, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(summary.title()).isEqualTo("測試商品");
    }

    // === findById() =============================================================

    @Test
    void 訂單詳情含品項與狀態時間軸() {
        Order order = order();
        Product product = new Product();
        product.setProductId(10);
        product.setName("測試商品");
        product.setPrice(new BigDecimal("500"));
        OrderItem item = new OrderItem(order, product, "測試商品", new BigDecimal("500"), 2);
        ReflectionTestUtils.setField(item, "id", 1);

        OrderStatusHistory checkout =
                new OrderStatusHistory(order, null, "UNPAID", order.getMember(), "USER", "CHECKOUT");
        OrderStatusHistory paid =
                new OrderStatusHistory(order, "UNPAID", "PAID", order.getMember(), "SYSTEM", "ECPAY_NOTIFY");

        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(item));
        when(orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(ORDER_ID)).thenReturn(List.of(checkout, paid));

        AdminOrderDetailResponse detail = service.findById(ORDER_ID);

        assertThat(detail.orderId()).isEqualTo(ORDER_ID);
        assertThat(detail.memberName()).isEqualTo("小明王");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).productId()).isEqualTo(10);
        assertThat(detail.history()).hasSize(2);
        assertThat(detail.history().get(1).note()).isEqualTo("ECPAY_NOTIFY");
    }

    @Test
    void 訂閱訂單詳情帶出方案快照與標題() {
        Order order = order();
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setSubscriptionPlanId(3);
        plan.setName("年費方案");
        plan.setDescription("最超值方案");
        plan.setPrice(new BigDecimal("15000"));
        plan.setDurationDays(365);
        order.setSubscriptionPlan(plan);

        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderItems.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of());
        when(orderStatusHistories.findAllByOrderIdOrderByChangedAtAsc(ORDER_ID)).thenReturn(List.of());

        AdminOrderDetailResponse detail = service.findById(ORDER_ID);

        assertThat(detail.items()).isEmpty();
        assertThat(detail.title()).isEqualTo("年費方案");
        assertThat(detail.subscription()).isNotNull();
        assertThat(detail.subscription().durationDays()).isEqualTo(365);
        assertThat(detail.subscription().price()).isEqualByComparingTo("15000");
    }

    @Test
    void 訂單不存在回404() {
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
