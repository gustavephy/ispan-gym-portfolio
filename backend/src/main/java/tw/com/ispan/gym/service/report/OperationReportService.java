package tw.com.ispan.gym.service.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tw.com.ispan.gym.dto.report.GenerateReportRequest;
import tw.com.ispan.gym.dto.report.OrderOverviewResponse;
import tw.com.ispan.gym.dto.report.OrderPeriodSummary;
import tw.com.ispan.gym.dto.report.ProductSalesRow;
import tw.com.ispan.gym.dto.report.ReportDetailResponse;
import tw.com.ispan.gym.dto.report.ReportSalesItemResponse;
import tw.com.ispan.gym.dto.report.ReportSummaryResponse;
import tw.com.ispan.gym.dto.report.SalesStatsResponse;
import tw.com.ispan.gym.dto.report.SubscriptionSalesRow;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.report.ReportSalesItem;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.enums.report.ReportItemType;
import tw.com.ispan.gym.event.order.RefundCompletedEvent;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.order.OrderItemRepository;
import tw.com.ispan.gym.repository.order.OrderRepository;
import tw.com.ispan.gym.repository.report.ReportSnapshotRepository;

/**
 * 營運報表。三條讀取路徑刻意分開：
 * - 歷史報表（listReports / getReport）讀快照，口徑凍結在生成當下
 * - 即時統計（liveStats）直接聚合訂單與排行，不落庫
 * - 訂單總覽（getOrderOverview）依狀態/付款方式分布，供後台儀表板
 *
 * 統計口徑（動口徑前先讀這裡）：
 * - 計入狀態：PAID / SHIPPED / COMPLETED（已付款且未取消）
 * - 時間軸：orders.paid_at（營收歸屬付款日）；getOrderOverview 例外用 createdAt（看的是下單量）
 * - 區間：日期含頭含尾，轉為 [start 00:00, end+1 00:00) 半開區間查詢
 * - 品項排行三分：商品/私教課來自 order_item 雙 FK；訂閱單無明細列，另行聚合 orders
 */
@Service
@Transactional(readOnly = true)
public class OperationReportService {

    /** 計入營收的訂單狀態：已付款且未取消（出貨中與完成都已收款） */
    static final List<OrderStatus> REVENUE_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    /** 自訂區間上限，防止一次聚合數年資料 */
    private static final int MAX_PERIOD_DAYS = 366;

    /** 品項排行的統一內部列：三種來源正規化後合併排序 */
    private record SalesEntry(ReportItemType type, Integer itemId, String name,
                              long quantitySold, BigDecimal grossAmount) {
    }

    private final ReportSnapshotRepository snapshots;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final EmployeeRepository employees;

    public OperationReportService(ReportSnapshotRepository snapshots, OrderRepository orders,
                                  OrderItemRepository orderItems, EmployeeRepository employees) {
        this.snapshots = snapshots;
        this.orders = orders;
        this.orderItems = orderItems;
        this.employees = employees;
    }

    /**
     * 退款完成 → 標記涵蓋原付款日的快照為過時。
     * 同步 listener：事件在退款交易內發布，這裡的 UPDATE 加入同一交易，
     * 退款與標記原子成敗。方法級 @Transactional 覆寫類別級的 readOnly。
     */
    @EventListener
    @Transactional
    public void onRefundCompleted(RefundCompletedEvent event) {
        snapshots.markStaleCovering(event.paidDate(), LocalDateTime.now());
    }

    /** 歷史報表列表，新的在前 */
    public List<ReportSummaryResponse> listReports() {
        return snapshots.findAllByOrderByGeneratedAtDesc().stream()
                .map(s -> new ReportSummaryResponse(s.getId(), s.getReportType(),
                        s.getPeriodStart(), s.getPeriodEnd(), s.getOrderCount(),
                        s.getTotalAmount(), s.getDiscountAmount(), s.getGeneratedAt(), s.getStaleAt()))
                .toList();
    }

    /** 單份報表含排行明細 */
    public ReportDetailResponse getReport(Integer reportId) {
        ReportSnapshot snapshot = snapshots.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到報表資料"));
        return toDetail(snapshot);
    }

    /** 刪除單份歷史報表；排行明細子項由 cascade 一併刪除 */
    @Transactional
    public void delete(Integer reportId) {
        ReportSnapshot snapshot = snapshots.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到報表資料"));
        snapshots.delete(snapshot);
    }

    /**
     * 以真實訂單結算並寫入快照。
     * 同型別同區間重新生成＝同一交易內刪舊插新，對外永遠只看得到一份完整的；
     * 併發觸發由 UX_report_type_period 唯一約束作最後防線。
     *
     * @param operatorAccountId 手動觸發的操作者帳號 id（JWT subject）；排程觸發傳 null。
     *                          身分域拆分後操作者記 Employee，以 account_id 反查。
     */
    @Transactional
    public ReportDetailResponse generate(GenerateReportRequest request, Integer operatorAccountId) {
        validatePeriod(request.periodStart(), request.periodEnd());
        LocalDateTime from = request.periodStart().atStartOfDay();
        LocalDateTime to = request.periodEnd().plusDays(1).atStartOfDay();

        snapshots.findByReportTypeAndPeriodStartAndPeriodEnd(
                        request.reportType(), request.periodStart(), request.periodEnd())
                .ifPresent(existing -> {
                    snapshots.delete(existing);
                    snapshots.flush(); // 先送出 DELETE，避免新快照撞唯一約束
                });

        OrderPeriodSummary summary = orders.summarizePeriod(from, to, REVENUE_STATUSES);
        List<SalesEntry> entries = aggregateSales(from, to);

        Employee operator = operatorAccountId == null ? null
                : employees.findByAccountId(operatorAccountId).orElse(null);
        ReportSnapshot snapshot = new ReportSnapshot(
                request.reportType(), request.periodStart(), request.periodEnd(),
                summary.orderCount(), zeroIfNull(summary.totalAmount()),
                zeroIfNull(summary.discountAmount()), operator);
        IntStream.range(0, entries.size()).forEach(i -> {
            SalesEntry e = entries.get(i);
            snapshot.addItem(new ReportSalesItem(snapshot, i + 1, e.itemId(),
                    e.type(), e.name(), e.quantitySold(), e.grossAmount()));
        });
        return toDetail(snapshots.save(snapshot));
    }

    /** 即時統計：依品項型別篩選時間區間，聚合 + 排行，不落快照 */
    public SalesStatsResponse liveStats(ReportItemType type, LocalDate periodStart,
                                        LocalDate periodEnd, int top) {
        validatePeriod(periodStart, periodEnd);
        if (top < 1 || top > 100) {
            throw new BusinessRuleException("排行筆數須介於 1 到 100");
        }
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();

        // 全量取回算總計，再篩型別、截前 N 名；品項種類數有限，不會是大結果集
        List<SalesEntry> entries = aggregateSales(from, to).stream()
                .filter(e -> type == null || e.type() == type)
                .toList();
        long totalQuantity = entries.stream().mapToLong(SalesEntry::quantitySold).sum();
        BigDecimal grossAmount = entries.stream().map(SalesEntry::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ReportSalesItemResponse> ranking = IntStream.range(0, Math.min(top, entries.size()))
                .mapToObj(i -> toItemResponse(i + 1, entries.get(i)))
                .toList();
        return new SalesStatsResponse(periodStart, periodEnd, type, totalQuantity, grossAmount, ranking);
    }

    /** 訂單總覽：依狀態/付款方式分布（時間軸為下單日 createdAt，看的是訂單量而非營收歸屬） */
    public OrderOverviewResponse getOrderOverview(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> countByStatus = new LinkedHashMap<>();
        Map<String, BigDecimal> revenueByStatus = new LinkedHashMap<>();
        long totalOrders = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        Set<OrderStatus> revenueSet = Set.copyOf(REVENUE_STATUSES);
        for (Object[] row : orders.aggregateByStatus(from, to)) {
            OrderStatus status = (OrderStatus) row[0];
            long count = (Long) row[1];
            BigDecimal sum = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];

            countByStatus.put(status.name(), count);
            revenueByStatus.put(status.name(), sum);
            totalOrders += count;
            if (revenueSet.contains(status)) {
                totalRevenue = totalRevenue.add(sum);
            }
        }

        Map<String, Long> countByPaymentMethod = new LinkedHashMap<>();
        for (Object[] row : orders.aggregateByPaymentMethod(from, to)) {
            countByPaymentMethod.put((String) row[0], (Long) row[1]);
        }

        return new OrderOverviewResponse(from, to, totalOrders, totalRevenue, countByStatus, revenueByStatus,
                countByPaymentMethod);
    }

    /**
     * 品項銷售三分合併：商品/私教課來自 order_item（LEFT JOIN 雙 FK，依非空側判別型別），
     * 訂閱單無明細列另行聚合 orders，合併後依累計金額降冪。
     */
    private List<SalesEntry> aggregateSales(LocalDateTime from, LocalDateTime to) {
        List<SalesEntry> entries = new ArrayList<>();
        for (ProductSalesRow row : orderItems.aggregateItemSales(from, to, REVENUE_STATUSES)) {
            if (row.productId() != null) {
                entries.add(new SalesEntry(ReportItemType.PRODUCT, row.productId(),
                        row.productName(), row.quantitySold(), row.grossAmount()));
            } else {
                entries.add(new SalesEntry(ReportItemType.COURSE, row.courseId(),
                        row.productName(), row.quantitySold(), row.grossAmount()));
            }
        }
        for (SubscriptionSalesRow row : orders.aggregateSubscriptionSales(from, to, REVENUE_STATUSES)) {
            entries.add(new SalesEntry(ReportItemType.SUBSCRIPTION, row.planId(),
                    row.planName(), row.quantitySold(), row.grossAmount()));
        }
        entries.sort(Comparator.comparing(SalesEntry::grossAmount).reversed());
        return entries;
    }

    private void validatePeriod(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessRuleException("起始日期不可晚於結束日期");
        }
        if (start.plusDays(MAX_PERIOD_DAYS).isBefore(end)) {
            throw new BusinessRuleException("統計區間不可超過 " + MAX_PERIOD_DAYS + " 天");
        }
    }

    private ReportDetailResponse toDetail(ReportSnapshot s) {
        List<ReportSalesItemResponse> items = s.getItems().stream()
                .map(i -> new ReportSalesItemResponse(i.getRankNo(), i.getProductId(),
                        i.getProductType(), i.getProductName(), i.getQuantitySold(), i.getGrossAmount()))
                .toList();
        return new ReportDetailResponse(s.getId(), s.getReportType(), s.getPeriodStart(),
                s.getPeriodEnd(), s.getOrderCount(), s.getTotalAmount(), s.getDiscountAmount(),
                s.getGeneratedAt(), s.getStaleAt(), items);
    }

    private ReportSalesItemResponse toItemResponse(int rank, SalesEntry e) {
        return new ReportSalesItemResponse(rank, e.itemId(), e.type(), e.name(),
                e.quantitySold(), e.grossAmount());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
