package tw.com.ispan.gym.service.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.dto.report.OrderOverviewResponse;
import tw.com.ispan.gym.dto.report.OrderPeriodSummary;
import tw.com.ispan.gym.dto.report.ProductSalesRow;
import tw.com.ispan.gym.dto.report.ReportBreakdownResponse;
import tw.com.ispan.gym.dto.report.ReportDailyTotalResponse;
import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSalesItemResponse;
import tw.com.ispan.gym.dto.report.SalesStatsResponse;
import tw.com.ispan.gym.dto.report.SubscriptionSalesRow;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.enums.report.ReportType;
import tw.com.ispan.gym.event.order.RefundCompletedEvent;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.report.CouponEffectRow;
import tw.com.ispan.gym.dto.report.GroupClassFillRow;
import tw.com.ispan.gym.dto.report.TrainerSlotRow;
import tw.com.ispan.gym.dto.report.ReportRefundSummary;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.coupon.CouponRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋類別註解列出的統計口徑：計入營收的三種狀態、日期區間轉半開區間
 * （end 要 +1 天才含尾）、品項排行三分來源的合併與金額降冪、
 * 區間驗證（起訖顛倒／超過上限）、重新生成時的 append-only 版本化（舊版標記
 * superseded、不刪），以及退款事件標記快照過時。
 *
 * 聚合本身是 JPQL，這裡只驗證「service 有沒有用正確的參數去查、拿到結果後怎麼組裝」，
 * SQL 正確性要靠實際資料庫驗證。
 */
class OperationReportServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private final ReportSnapshotRepository snapshots = mock(ReportSnapshotRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository orderItems = mock(OrderItemRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberSubscriptionRepository subscriptions = mock(MemberSubscriptionRepository.class);
    private final GroupClassSessionRepository groupClassSessions = mock(GroupClassSessionRepository.class);
    private final CoachSlotRepository coachSlots = mock(CoachSlotRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final OperationReportService service = new OperationReportService(
            snapshots, orders, orderItems, employees, refunds, members, subscriptions, groupClassSessions,
            coachSlots, coupons);

    // === 共用測試資料 ========================================================

    private GenerateReportRequest request() {
        return new GenerateReportRequest(ReportType.MONTHLY, START, END);
    }

    private void stubEmptyAggregates() {
        when(orders.summarizePeriod(any(), any(), any()))
                .thenReturn(new OrderPeriodSummary(0L, null, null));
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of());
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));
    }

    private void stubSnapshotSaveReturnsArgument() {
        when(snapshots.save(any(ReportSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    // === generate()：區間換算與口徑 ============================================

    @Test
    void 生成報表以含頭含尾的日期換算為半開區間查詢() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();

        service.generate(request(), null);

        // 8/1 00:00（含頭）到 9/1 00:00（不含），最後一天整天才算得進去
        verify(orders).summarizePeriod(
                eq(LocalDate.of(2026, 8, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 9, 1).atStartOfDay()),
                any());
    }

    @Test
    void 只計入已付款出貨中與已完成三種狀態() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();

        service.generate(request(), null);

        verify(orders).summarizePeriod(any(), any(),
                eq(List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED)));
    }

    @Test
    void 區間內無訂單時金額正規化為零而不是null() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();

        ReportDetailResponse response = service.generate(request(), null);

        assertThat(response.orderCount()).isZero();
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 起始日晚於結束日擋下() {
        assertThatThrownBy(() -> service.generate(
                new GenerateReportRequest(ReportType.MONTHLY, END, START), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不可晚於");

        verify(snapshots, never()).save(any());
    }

    @Test
    void 區間超過上限天數擋下() {
        assertThatThrownBy(() -> service.generate(
                new GenerateReportRequest(ReportType.MONTHLY, START, START.plusDays(400)), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不可超過");

        verify(snapshots, never()).save(any());
    }

    @Test
    void 同型別同區間重新生成舊版標記superseded新版為現行不刪舊() {
        stubEmptyAggregates();
        ReportSnapshot existing = new ReportSnapshot(ReportType.MONTHLY, START, END, 5L,
                new BigDecimal("1000"), BigDecimal.ZERO, null);
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEndAndSupersededAtIsNull(
                ReportType.MONTHLY, START, END)).thenReturn(Optional.of(existing));
        when(snapshots.save(any(ReportSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshots.saveAndFlush(any(ReportSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generate(request(), null);

        // append-only：舊版不刪、蓋上 supersededAt；先 flush 讓出「現行版」篩選唯一索引
        assertThat(existing.getSupersededAt()).isNotNull();
        verify(snapshots).saveAndFlush(existing);
        verify(snapshots, never()).delete(any());
        verify(snapshots).save(any(ReportSnapshot.class));
    }

    // === generate()：延伸統計（2026-08-17 擴充）================================

    @Test
    void 延伸統計與總覽同口徑一併固化進快照() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();
        // 原始列由 service 在 Java 端分桶：8/1 兩筆合 3000、8/2 一筆 1200
        when(orders.findPaidAtAndAmount(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[] { LocalDateTime.of(2026, 8, 1, 10, 0), new BigDecimal("1800") },
                new Object[] { LocalDateTime.of(2026, 8, 1, 14, 30), new BigDecimal("1200") },
                new Object[] { LocalDateTime.of(2026, 8, 2, 9, 0), new BigDecimal("1200") }));
        when(orders.aggregateByStatus(any(), any())).thenReturn(List.<Object[]>of(
                new Object[] { OrderStatus.PAID, 3L, new BigDecimal("4200") },
                new Object[] { OrderStatus.CANCELLED, 1L, new BigDecimal("500") }));
        when(orders.aggregateByPaymentMethod(any(), any())).thenReturn(List.<Object[]>of(
                new Object[] { "Credit", 3L }));
        when(refunds.summarizeCompleted(any(), any()))
                .thenReturn(new ReportRefundSummary(2L, new BigDecimal("800")));
        when(members.countCreatedBetween(any(), any())).thenReturn(5L);
        when(subscriptions.countCreatedBetween(any(), any())).thenReturn(3L);

        ReportDetailResponse response = service.generate(request(), null);

        // 每日營收：趨勢資料隨快照凍結，讀取時不重算；同日多筆要合併加總
        assertThat(response.dailyTotals()).extracting(ReportDailyTotalResponse::statDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));
        assertThat(response.dailyTotals()).extracting(ReportDailyTotalResponse::orderCount)
                .containsExactly(2L, 1L);
        assertThat(response.dailyTotals().get(0).revenue()).isEqualByComparingTo("3000");
        // 兩種分佈依維度分流，不混在同一份清單
        assertThat(response.statusBreakdown()).extracting(ReportBreakdownResponse::itemKey)
                .containsExactly("PAID", "CANCELLED");
        assertThat(response.paymentBreakdown()).extracting(ReportBreakdownResponse::itemKey)
                .containsExactly("Credit");
        assertThat(response.refundCount()).isEqualTo(2L);
        assertThat(response.refundAmount()).isEqualByComparingTo("800");
        assertThat(response.newMemberCount()).isEqualTo(5L);
        assertThat(response.newSubscriptionCount()).isEqualTo(3L);
    }

    @Test
    void 每日營收與總覽查詢用同一組區間與狀態() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();

        service.generate(request(), null);

        verify(orders).findPaidAtAndAmount(
                eq(LocalDate.of(2026, 8, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 9, 1).atStartOfDay()),
                eq(List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED)));
    }

    @Test
    void 台北清晨付款分到付款當日不因UTC存放歸前一天() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();
        // Hibernate 讀回的 paidAt 是台北牆鐘：8/31 07:00 在 DB 實際存成 UTC 8/30 23:00，
        // 若在 DB 端 CAST 截日期會錯歸 8/30（真實快照 #3003 驗出的 bug）——分桶必須在 Java 端做。
        // mock 擋在 repository 介面，這裡守的是「service 用讀回的牆鐘時間分桶」這個口徑
        when(orders.findPaidAtAndAmount(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[] { LocalDateTime.of(2026, 8, 31, 7, 0), new BigDecimal("999") }));

        ReportDetailResponse response = service.generate(request(), null);

        assertThat(response.dailyTotals()).singleElement().satisfies(d -> {
            assertThat(d.statDate()).isEqualTo(LocalDate.of(2026, 8, 31));
            assertThat(d.revenue()).isEqualByComparingTo("999");
        });
    }

    @Test
    void 區間內無退款時退款統計正規化為零() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));

        ReportDetailResponse response = service.generate(request(), null);

        assertThat(response.refundCount()).isZero();
        assertThat(response.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === generate()：操作者 ===================================================

    @Test
    void 手動觸發以帳號id反查員工記為操作者() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();
        Employee operator = new Employee();
        when(employees.findByAccountId(7)).thenReturn(Optional.of(operator));

        service.generate(request(), 7);

        verify(employees).findByAccountId(7);
    }

    @Test
    void 排程觸發沒有操作者時不查員工() {
        stubEmptyAggregates();
        stubSnapshotSaveReturnsArgument();

        service.generate(request(), null);

        verify(employees, never()).findByAccountId(any());
    }

    // === 品項排行：三種來源合併 ================================================

    @Test
    void 排行合併商品私教課與訂閱三種來源並依金額降冪() {
        // 商品（productId 非空）與私教課（courseId 非空）都來自 order_item
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of(
                new ProductSalesRow(100, null, "乳清蛋白", 10L, new BigDecimal("5000")),
                new ProductSalesRow(null, 200, "一對一重訓", 2L, new BigDecimal("12000"))));
        // 訂閱單沒有明細列，另外聚合 orders
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of(
                new SubscriptionSalesRow(300, "月費方案", 8L, new BigDecimal("8000"))));
        when(orders.summarizePeriod(any(), any(), any()))
                .thenReturn(new OrderPeriodSummary(20L, new BigDecimal("25000"), BigDecimal.ZERO));
        stubSnapshotSaveReturnsArgument();

        ReportDetailResponse response = service.generate(request(), null);

        // 依累計金額降冪：課程 12000 > 訂閱 8000 > 商品 5000
        assertThat(response.items()).extracting(ReportSalesItemResponse::productType)
                .containsExactly(ReportItemType.COURSE, ReportItemType.SUBSCRIPTION, ReportItemType.PRODUCT);
        // 名次依排序後的順序編號
        assertThat(response.items()).extracting(ReportSalesItemResponse::rankNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 明細列的商品id為空時判定為私教課() {
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of(
                new ProductSalesRow(null, 200, "一對一重訓", 2L, new BigDecimal("12000"))));
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));
        when(orders.summarizePeriod(any(), any(), any()))
                .thenReturn(new OrderPeriodSummary(2L, new BigDecimal("12000"), BigDecimal.ZERO));
        stubSnapshotSaveReturnsArgument();

        ReportDetailResponse response = service.generate(request(), null);

        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.productType()).isEqualTo(ReportItemType.COURSE);
                    assertThat(item.productId()).isEqualTo(200);
                });
    }

    // === liveStats() =========================================================

    @Test
    void 即時統計依型別篩選並加總數量與金額() {
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of(
                new ProductSalesRow(100, null, "乳清蛋白", 10L, new BigDecimal("5000")),
                new ProductSalesRow(101, null, "搖搖杯", 4L, new BigDecimal("1000")),
                new ProductSalesRow(null, 200, "一對一重訓", 2L, new BigDecimal("12000"))));
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));

        SalesStatsResponse stats = service.liveStats(ReportItemType.PRODUCT, START, END, 10);

        // 只算商品：10 + 4 = 14 件、5000 + 1000 = 6000 元，課程不列入
        assertThat(stats.totalQuantity()).isEqualTo(14L);
        assertThat(stats.grossAmount()).isEqualByComparingTo("6000");
        assertThat(stats.ranking()).hasSize(2);
    }

    @Test
    void 即時統計不指定型別時全部列入() {
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of(
                new ProductSalesRow(100, null, "乳清蛋白", 10L, new BigDecimal("5000")),
                new ProductSalesRow(null, 200, "一對一重訓", 2L, new BigDecimal("12000"))));
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of(
                new SubscriptionSalesRow(300, "月費方案", 8L, new BigDecimal("8000"))));

        SalesStatsResponse stats = service.liveStats(null, START, END, 10);

        assertThat(stats.totalQuantity()).isEqualTo(20L);
        assertThat(stats.grossAmount()).isEqualByComparingTo("25000");
    }

    @Test
    void 即時統計只取前N名但總計仍涵蓋全部() {
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of(
                new ProductSalesRow(100, null, "A", 10L, new BigDecimal("5000")),
                new ProductSalesRow(101, null, "B", 4L, new BigDecimal("3000")),
                new ProductSalesRow(102, null, "C", 2L, new BigDecimal("1000"))));
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));

        SalesStatsResponse stats = service.liveStats(ReportItemType.PRODUCT, START, END, 2);

        // 排行只回 2 筆
        assertThat(stats.ranking()).hasSize(2);
        assertThat(stats.ranking()).extracting(ReportSalesItemResponse::productName).containsExactly("A", "B");
        // 但總計不受 top 影響，仍是全部三筆
        assertThat(stats.totalQuantity()).isEqualTo(16L);
        assertThat(stats.grossAmount()).isEqualByComparingTo("9000");
    }

    @Test
    void 即時統計排行筆數超出範圍擋下() {
        assertThatThrownBy(() -> service.liveStats(null, START, END, 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1 到 100");
        assertThatThrownBy(() -> service.liveStats(null, START, END, 101))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 即時統計無資料時回零與空排行() {
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of());
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
        when(refunds.summarizeCompleted(any(), any())).thenReturn(new ReportRefundSummary(0L, null));

        SalesStatsResponse stats = service.liveStats(null, START, END, 10);

        assertThat(stats.totalQuantity()).isZero();
        assertThat(stats.grossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.ranking()).isEmpty();
    }

    // === getOrderOverview() ===================================================

    @Test
    void 訂單總覽的營收只累計已收款狀態但訂單數全計() {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        when(orders.aggregateByStatus(from, to)).thenReturn(List.of(
                new Object[] { OrderStatus.PAID, 10L, new BigDecimal("10000") },
                new Object[] { OrderStatus.COMPLETED, 5L, new BigDecimal("5000") },
                new Object[] { OrderStatus.CANCELLED, 3L, new BigDecimal("3000") },
                new Object[] { OrderStatus.UNPAID, 2L, new BigDecimal("2000") }));
        when(orders.aggregateByPaymentMethod(from, to)).thenReturn(List.<Object[]>of(
                new Object[] { "Credit", 12L }));

        OrderOverviewResponse overview = service.getOrderOverview(from, to);

        // 訂單數含取消與未付款：10+5+3+2
        assertThat(overview.totalOrders()).isEqualTo(20L);
        // 營收只有 PAID + COMPLETED，取消與未付款不計
        assertThat(overview.totalRevenue()).isEqualByComparingTo("15000");
        assertThat(overview.countByStatus()).containsEntry("CANCELLED", 3L);
        assertThat(overview.countByPaymentMethod()).containsEntry("Credit", 12L);
    }

    @Test
    void 訂單總覽金額為null時視為零不噴NPE() {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        when(orders.aggregateByStatus(from, to)).thenReturn(List.<Object[]>of(
                new Object[] { OrderStatus.PAID, 1L, null }));
        when(orders.aggregateByPaymentMethod(from, to)).thenReturn(List.of());

        OrderOverviewResponse overview = service.getOrderOverview(from, to);

        assertThat(overview.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(overview.revenueByStatus()).containsEntry("PAID", BigDecimal.ZERO);
        // 退款投影未 stub（回 null）也要正規化為零，口徑同 attachExtendedStats
        assertThat(overview.refundCount()).isZero();
        assertThat(overview.refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 團課滿座率與會員成長皆以半開區間查詢且起訖顛倒會被擋下() {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        when(groupClassSessions.aggregateFillRate(from, to))
                .thenReturn(List.of(new GroupClassFillRow("熱瑜珈", 4L, 30L, 40L)));
        // 兩筆同日不同時刻的註冊，Java 端分桶後合成一列
        when(members.findCreatedAtBetween(from, to))
                .thenReturn(List.of(START.atTime(9, 30), START.atTime(23, 5)));

        assertThat(service.groupClassFill(START, END)).hasSize(1)
                .first().satisfies(row -> assertThat(row.bookedTotal()).isEqualTo(30L));
        assertThat(service.memberGrowth(START, END)).hasSize(1)
                .first().satisfies(row -> {
                    assertThat(row.statDate()).isEqualTo(START);
                    assertThat(row.newMembers()).isEqualTo(2L);
                });

        assertThatThrownBy(() -> service.groupClassFill(END, START))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.memberGrowth(END, START))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 教練使用率有期間驗證而優惠券成效不分期間() {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        when(coachSlots.aggregateUtilization(from, to))
                .thenReturn(List.of(new TrainerSlotRow("林志強", 10L, 7L)));
        when(coupons.aggregateEffect())
                .thenReturn(List.of(new CouponEffectRow("新春折扣", "CNY100", 100, 60, 25L)));

        assertThat(service.trainerUtilization(START, END)).hasSize(1)
                .first().satisfies(row -> assertThat(row.bookedSlots()).isEqualTo(7L));
        assertThat(service.couponEffect()).hasSize(1)
                .first().satisfies(row -> assertThat(row.usedCount()).isEqualTo(25L));

        assertThatThrownBy(() -> service.trainerUtilization(END, START))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 訂單總覽帶回退款與新會員新訂閱數() {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        when(orders.aggregateByStatus(from, to)).thenReturn(List.of());
        when(orders.aggregateByPaymentMethod(from, to)).thenReturn(List.of());
        when(refunds.summarizeCompleted(from, to)).thenReturn(new ReportRefundSummary(2L, new BigDecimal("800")));
        when(members.countCreatedBetween(from, to)).thenReturn(5L);
        when(subscriptions.countCreatedBetween(from, to)).thenReturn(3L);

        OrderOverviewResponse overview = service.getOrderOverview(from, to);

        assertThat(overview.refundCount()).isEqualTo(2L);
        assertThat(overview.refundAmount()).isEqualByComparingTo("800");
        assertThat(overview.newMemberCount()).isEqualTo(5L);
        assertThat(overview.newSubscriptionCount()).isEqualTo(3L);
    }

    // === listReports() / getReport() / delete() ================================

    @Test
    void 查不存在的報表回404() {
        when(snapshots.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReport(1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 刪除不存在的報表回404且不刪除() {
        when(snapshots.findById(1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(snapshots, never()).delete(any());
    }

    @Test
    void 報表列表只列現行版並回傳摘要欄位() {
        when(snapshots.findAllBySupersededAtIsNullOrderByGeneratedAtDesc()).thenReturn(List.of(
                new ReportSnapshot(ReportType.MONTHLY, START, END, 10L,
                        new BigDecimal("10000"), new BigDecimal("500"), null)));

        assertThat(service.listReports()).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.reportType()).isEqualTo(ReportType.MONTHLY);
                    assertThat(summary.orderCount()).isEqualTo(10L);
                    assertThat(summary.totalAmount()).isEqualByComparingTo("10000");
                    assertThat(summary.supersededAt()).isNull();
                });
    }

    @Test
    void 版本清單回傳同區間全部版本且現行版在前() {
        ReportSnapshot current = new ReportSnapshot(ReportType.MONTHLY, START, END, 12L,
                new BigDecimal("12000"), BigDecimal.ZERO, null);
        ReportSnapshot old = new ReportSnapshot(ReportType.MONTHLY, START, END, 10L,
                new BigDecimal("10000"), BigDecimal.ZERO, null);
        old.markSuperseded(LocalDateTime.of(2026, 9, 1, 0, 10));
        when(snapshots.findById(7)).thenReturn(Optional.of(current));
        when(snapshots.findAllByReportTypeAndPeriodStartAndPeriodEndOrderByGeneratedAtDesc(
                ReportType.MONTHLY, START, END)).thenReturn(List.of(current, old));

        var versions = service.versions(7);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).supersededAt()).isNull(); // 現行版
        assertThat(versions.get(1).supersededAt()).isNotNull(); // 歷史版本仍可讀
        assertThat(versions.get(1).totalAmount()).isEqualByComparingTo("10000");
    }

    // === onRefundCompleted() ==================================================

    @Test
    void 退款完成標記涵蓋原付款日的快照為過時() {
        LocalDate paidDate = LocalDate.of(2026, 8, 15);

        service.onRefundCompleted(new RefundCompletedEvent(900, paidDate));

        // 報表口徑以付款日歸屬營收，退款會讓涵蓋該日的既有快照失真
        verify(snapshots).markStaleCovering(eq(paidDate), any(LocalDateTime.class));
    }
}
