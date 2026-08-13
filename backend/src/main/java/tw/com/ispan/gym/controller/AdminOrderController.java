package tw.com.ispan.gym.controller;

import tw.com.ispan.gym.dto.order.PendingRefundResponse;
import tw.com.ispan.gym.dto.report.OrderOverviewResponse;
import tw.com.ispan.gym.service.report.OperationReportService;
import tw.com.ispan.gym.service.order.OrderService;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 後台訂單管理：報表、退款處理。受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護。
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
    private final OperationReportService reportService;
    private final OrderService orderService;

    public AdminOrderController(OperationReportService reportService, OrderService orderService) {
        this.reportService = reportService;
        this.orderService = orderService;
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
    public void completeRefund(@PathVariable Long refundId) {
        orderService.completeRefund(refundId);
    }
}
