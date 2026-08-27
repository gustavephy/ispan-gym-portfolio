package tw.com.ispan.gym.controller.payment;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.payment.AdminPaymentRefundResponse;
import tw.com.ispan.gym.dto.payment.AdminPaymentTransactionResponse;
import tw.com.ispan.gym.dto.payment.AdminPaymentWebhookResponse;
import tw.com.ispan.gym.service.payment.AdminPaymentQueryService;

/**
 * 後台金流查詢：交易紀錄 / 退款紀錄 / Webhook 回呼。
 * 受 SecurityConfig 既有的 {@code /api/admin/**} → hasRole("ADMIN") 規則保護。
 */
@RestController
@RequestMapping("/api/admin/payments")
public class AdminPaymentController {
    private final AdminPaymentQueryService queryService;

    public AdminPaymentController(AdminPaymentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/transactions")
    public Page<AdminPaymentTransactionResponse> transactions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return queryService.searchTransactions(status, provider, from, to, keyword, pageable);
    }

    @GetMapping("/refunds")
    public Page<AdminPaymentRefundResponse> refunds(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return queryService.searchRefunds(status, from, to, keyword, pageable);
    }

    @GetMapping("/webhooks")
    public Page<AdminPaymentWebhookResponse> webhooks(
            @RequestParam(required = false) String processingStatus,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean checkMacValid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "receivedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return queryService.searchWebhooks(processingStatus, provider, checkMacValid, from, to, keyword, pageable);
    }
}
