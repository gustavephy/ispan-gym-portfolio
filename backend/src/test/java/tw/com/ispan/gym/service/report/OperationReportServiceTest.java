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
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 涵蓋類別註解列出的統計口徑：計入營收的三種狀態、日期區間轉半開區間
 * （end 要 +1 天才含尾）、品項排行三分來源的合併與金額降冪、
 * 區間驗證（起訖顛倒／超過上限）、重新生成時先刪後插，以及退款事件標記快照過時。
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
    private final OperationReportService service = new OperationReportService(snapshots, orders, orderItems, employees);

    // === 共用測試資料 ========================================================

    private GenerateReportRequest request() {
        return new GenerateReportRequest(ReportType.MONTHLY, START, END);
    }

    private void stubEmptyAggregates() {
        when(orders.summarizePeriod(any(), any(), any()))
                .thenReturn(new OrderPeriodSummary(0L, null, null));
        when(orderItems.aggregateItemSales(any(), any(), any())).thenReturn(List.of());
        when(orders.aggregateSubscriptionSales(any(), any(), any())).thenReturn(List.of());
    }

    private void stubSnapshotSaveReturnsArgument() {
        when(snapshots.save(any(ReportSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEnd(any(), any(), any()))
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
    void 同型別同區間重新生成先刪舊再插新() {
        stubEmptyAggregates();
        ReportSnapshot existing = new ReportSnapshot(ReportType.MONTHLY, START, END, 5L,
                new BigDecimal("1000"), BigDecimal.ZERO, null);
        when(snapshots.findByReportTypeAndPeriodStartAndPeriodEnd(ReportType.MONTHLY, START, END))
                .thenReturn(Optional.of(existing));
        when(snapshots.save(any(ReportSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generate(request(), null);

        verify(snapshots).delete(existing);
        // 先 flush 送出 DELETE，新快照才不會撞唯一約束
        verify(snapshots).flush();
        verify(snapshots).save(any(ReportSnapshot.class));
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
    void 報表列表回傳摘要欄位() {
        when(snapshots.findAllByOrderByGeneratedAtDesc()).thenReturn(List.of(
                new ReportSnapshot(ReportType.MONTHLY, START, END, 10L,
                        new BigDecimal("10000"), new BigDecimal("500"), null)));

        assertThat(service.listReports()).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.reportType()).isEqualTo(ReportType.MONTHLY);
                    assertThat(summary.orderCount()).isEqualTo(10L);
                    assertThat(summary.totalAmount()).isEqualByComparingTo("10000");
                });
    }

    // === onRefundCompleted() ==================================================

    @Test
    void 退款完成標記涵蓋原付款日的快照為過時() {
        LocalDate paidDate = LocalDate.of(2026, 8, 15);

        service.onRefundCompleted(new RefundCompletedEvent(900L, paidDate));

        // 報表口徑以付款日歸屬營收，退款會讓涵蓋該日的既有快照失真
        verify(snapshots).markStaleCovering(eq(paidDate), any(LocalDateTime.class));
    }
}
