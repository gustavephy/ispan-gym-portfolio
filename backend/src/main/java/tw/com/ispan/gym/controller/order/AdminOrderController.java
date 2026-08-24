package tw.com.ispan.gym.controller.order;

import tw.com.ispan.gym.dto.order.AdminOrderDetailResponse;
import tw.com.ispan.gym.dto.order.AdminOrderSummaryResponse;
import tw.com.ispan.gym.dto.order.OrderDetailResponse;
import tw.com.ispan.gym.dto.order.PendingRefundResponse;
import tw.com.ispan.gym.dto.report.OrderOverviewResponse;
import tw.com.ispan.gym.enums.order.OrderStatus;
import tw.com.ispan.gym.service.report.OperationReportService;
import tw.com.ispan.gym.service.order.AdminOrderQueryService;
import tw.com.ispan.gym.service.order.OrderService;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 後台訂單管理：搜尋/查詢、出貨/完成、報表、退款處理。
 * 受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護。
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
    private final OperationReportService reportService;
    private final OrderService orderService;
    private final AdminOrderQueryService orderQueryService;

    public AdminOrderController(OperationReportService reportService, OrderService orderService,
            AdminOrderQueryService orderQueryService) {
        this.reportService = reportService;
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * 訂單搜尋，條件皆為選填：status／memberId／createdFrom／createdTo（下單時間區間，含頭不含尾）／
     * keyword（比對訂單編號、綠界特店編號、收件人姓名、會員信箱，不分大小寫、模糊比對）。
     */
    @GetMapping
    public Page<AdminOrderSummaryResponse> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Integer memberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20) Pageable pageable) {
        return orderQueryService.search(status, memberId, createdFrom, createdTo, keyword, pageable);
    }

    /** 單筆訂單詳情：明細 + 狀態時間軸 */
    @GetMapping("/{orderId}")
    public AdminOrderDetailResponse detail(@PathVariable Integer orderId) {
        return orderQueryService.findById(orderId);
    }

    /** 標記已出貨：PAID → SHIPPED，僅適用於含實體商品的訂單 */
    @PostMapping("/{orderId}/ship")
    public OrderDetailResponse ship(@PathVariable Integer orderId) {
        return orderService.shipOrder(orderId);
    }

    /** 標記訂單完成：SHIPPED → COMPLETED，或無出貨步驟的訂單直接 PAID → COMPLETED */
    @PostMapping("/{orderId}/complete")
    public OrderDetailResponse complete(@PathVariable Integer orderId) {
        return orderService.completeOrder(orderId);
    }

    @GetMapping("/reports/overview")
    public OrderOverviewResponse overview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return reportService.getOrderOverview(from, to);
    }

    /** 待人工執行的退款清單（會員取消已付款訂單後產生） */
    @GetMapping("/refunds/pending")
    public List<PendingRefundResponse> pendingRefunds() {
        return orderService.listPendingRefunds();
    }

    /** 後台人工完成一筆退款：還原庫存/優惠券、訂單轉 REFUNDED */
    @PostMapping("/refunds/{refundId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeRefund(@PathVariable Integer refundId) {
        orderService.completeRefund(refundId);
    }
}
