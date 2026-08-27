package tw.com.ispan.gym.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.OrderItemResponse;
import tw.com.ispan.gym.dto.order.OrderSummaryResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.order.Order;
import tw.com.ispan.gym.entity.order.OrderItem;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.order.OrderStatusHistoryRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 聚焦「我的訂單」頁需要的兩件事：卡片標題 {@code title}（訂閱＝方案名、單一品項＝品項名、
 * 多品項＝「第一項 等 N 項」），以及明細要完整帶出購買內容——訂閱訂單的 {@code subscription}、
 * 私教課品項的堂數與教練。
 */
class OrderQueryServiceTest {

    private static final Integer MEMBER_ID = 1;

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistories = mock(OrderStatusHistoryRepository.class);

    private final OrderQueryService service = new OrderQueryService(orders, orderItems, orderStatusHistories);

    // === 測試資料 ============================================================

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

    private Order order(int id) {
        Order order = new Order(member(), "GYM" + id, "ORD" + id, new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("1000"), new BigDecimal("1000"), null, "王小明", "0912345678", null, "buyer@example.com");
        ReflectionTestUtils.setField(order, "orderId", id);
        return order;
    }

    private Product product(int id, String name) {
        Product product = new Product();
        product.setProductId(id);
        product.setName(name);
        product.setPrice(new BigDecimal("500"));
        return product;
    }

    private PrivateCourse course() {
        Trainer trainer = new Trainer();
        trainer.setFirstName("阿肌");
        trainer.setLastName("陳");
        PrivateCourse course = new PrivateCourse();
        course.setPrivateCourseId(7);
        course.setName("一對一重訓入門（5堂）");
        course.setPrice(new BigDecimal("6500"));
        course.setSessionPerUnit(5);
        course.setStatus(CourseStatus.ACTIVE);
        course.setTrainer(trainer);
        return course;
    }

    private SubscriptionPlan plan() {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setSubscriptionPlanId(2);
        plan.setName("季費方案");
        plan.setDescription("三個月方案，平均每月 1400 元。");
        plan.setPrice(new BigDecimal("4200"));
        plan.setDurationDays(90);
        plan.setStatus("ACTIVE");
        return plan;
    }

    private OrderItem productItem(Order order, int itemId, Product product, int qty) {
        OrderItem item = new OrderItem(order, product, product.getName(), product.getPrice(), qty);
        ReflectionTestUtils.setField(item, "orderItemId", itemId);
        return item;
    }

    private OrderItem courseItem(Order order, int itemId, PrivateCourse course, int qty) {
        OrderItem item = new OrderItem(order, course, course.getName(), course.getPrice(), qty);
        ReflectionTestUtils.setField(item, "orderItemId", itemId);
        return item;
    }

    // === findMine：卡片標題 ==================================================

    @Test
    void 列表標題_單一商品用品項名多品項用第一項等N項訂閱用方案名() {
        Order single = order(1);
        Order multi = order(2);
        Order subscription = order(3);
        subscription.setSubscriptionPlan(plan());
        when(orders.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).thenReturn(List.of(single, multi, subscription));
        when(orderItems.findAllByOrderIdInOrderByOrderIdAscIdAsc(any())).thenReturn(List.of(
                productItem(single, 10, product(100, "乳清蛋白"), 1),
                productItem(multi, 20, product(200, "搖搖杯"), 1),
                productItem(multi, 21, product(201, "彈力帶"), 2)));

        List<OrderSummaryResponse> result = service.findMine(MEMBER_ID);

        assertThat(result).extracting(OrderSummaryResponse::title)
                .containsExactly("乳清蛋白", "搖搖杯 等 2 項", "季費方案");
    }

    @Test
    void 列表標題_私教課訂單用課程名() {
        Order courseOrder = order(1);
        when(orders.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).thenReturn(List.of(courseOrder));
        when(orderItems.findAllByOrderIdInOrderByOrderIdAscIdAsc(any()))
                .thenReturn(List.of(courseItem(courseOrder, 10, course(), 1)));

        assertThat(service.findMine(MEMBER_ID)).singleElement()
                .extracting(OrderSummaryResponse::title).isEqualTo("一對一重訓入門（5堂）");
    }

    // === findMineById：完整購買內容 =========================================

    @Test
    void 明細_訂閱訂單帶出方案快照且標題為方案名() {
        Order subscription = order(1);
        subscription.setSubscriptionPlan(plan());
        when(orders.findByIdAndMemberId(1, MEMBER_ID)).thenReturn(Optional.of(subscription));
        when(orderItems.findAllByOrderIdOrderByIdAsc(1)).thenReturn(List.of());

        OrderDetailResponse detail = service.findMineById(MEMBER_ID, 1);

        assertThat(detail.items()).isEmpty();
        assertThat(detail.title()).isEqualTo("季費方案");
        assertThat(detail.subscription()).isNotNull();
        assertThat(detail.subscription().name()).isEqualTo("季費方案");
        assertThat(detail.subscription().durationDays()).isEqualTo(90);
        assertThat(detail.subscription().price()).isEqualByComparingTo("4200");
        assertThat(detail.subscription().description()).contains("三個月");
    }

    @Test
    void 明細_私教課品項帶出型別堂數與教練() {
        Order courseOrder = order(1);
        when(orders.findByIdAndMemberId(1, MEMBER_ID)).thenReturn(Optional.of(courseOrder));
        when(orderItems.findAllByOrderIdOrderByIdAsc(1))
                .thenReturn(List.of(courseItem(courseOrder, 10, course(), 2)));

        OrderDetailResponse detail = service.findMineById(MEMBER_ID, 1);

        assertThat(detail.subscription()).isNull();
        OrderItemResponse item = detail.items().get(0);
        assertThat(item.itemType()).isEqualTo("COURSE");
        assertThat(item.privateCourseId()).isEqualTo(7);
        assertThat(item.productId()).isNull();
        assertThat(item.sessionPerUnit()).isEqualTo(5);
        assertThat(item.totalSessions()).isEqualTo(10); // 5 堂/單位 × 2 單位
        assertThat(item.trainerName()).isEqualTo("阿肌陳");
        assertThat(item.lineAmount()).isEqualByComparingTo("13000");
    }

    @Test
    void 明細_商品品項的私教課欄位為空() {
        Order productOrder = order(1);
        when(orders.findByIdAndMemberId(1, MEMBER_ID)).thenReturn(Optional.of(productOrder));
        when(orderItems.findAllByOrderIdOrderByIdAsc(1))
                .thenReturn(List.of(productItem(productOrder, 10, product(100, "乳清蛋白"), 3)));

        OrderItemResponse item = service.findMineById(MEMBER_ID, 1).items().get(0);

        assertThat(item.itemType()).isEqualTo("PRODUCT");
        assertThat(item.productId()).isEqualTo(100);
        assertThat(item.privateCourseId()).isNull();
        assertThat(item.sessionPerUnit()).isNull();
        assertThat(item.totalSessions()).isNull();
        assertThat(item.trainerName()).isNull();
    }

    @Test
    void 明細_非本人訂單回404() {
        when(orders.findByIdAndMemberId(99, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findMineById(MEMBER_ID, 99))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
