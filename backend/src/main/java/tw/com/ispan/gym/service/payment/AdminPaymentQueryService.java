package tw.com.ispan.gym.service.payment;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.payment.AdminPaymentRefundResponse;
import tw.com.ispan.gym.dto.payment.AdminPaymentTransactionResponse;
import tw.com.ispan.gym.dto.payment.AdminPaymentWebhookResponse;
import tw.com.ispan.gym.repository.payment.PaymentRefundRepository;
import tw.com.ispan.gym.repository.payment.PaymentTransactionRepository;
import tw.com.ispan.gym.repository.payment.PaymentWebhookRepository;

/**
 * 後台金流查詢：交易紀錄、退款紀錄、Webhook 回呼三個清單。純讀取，供後台「交易紀錄」頁
 * 對帳與排查卡住的付款用。授權由 {@code /api/admin/**} → ADMIN 把關（見 SecurityConfig）。
 */
@Service
@Transactional(readOnly = true)
public class AdminPaymentQueryService {
    private final PaymentTransactionRepository transactions;
    private final PaymentRefundRepository refunds;
    private final PaymentWebhookRepository webhooks;

    public AdminPaymentQueryService(PaymentTransactionRepository transactions, PaymentRefundRepository refunds,
            PaymentWebhookRepository webhooks) {
        this.transactions = transactions;
        this.refunds = refunds;
        this.webhooks = webhooks;
    }

    public Page<AdminPaymentTransactionResponse> searchTransactions(String status, String provider,
            LocalDateTime from, LocalDateTime to, String keyword, Pageable pageable) {
        return transactions.search(blankToNull(status), blankToNull(provider), from, to, like(keyword), pageable)
                .map(AdminPaymentTransactionResponse::from);
    }

    public Page<AdminPaymentRefundResponse> searchRefunds(String status, LocalDateTime from, LocalDateTime to,
            String keyword, Pageable pageable) {
        return refunds.search(blankToNull(status), from, to, like(keyword), pageable)
                .map(AdminPaymentRefundResponse::from);
    }

    public Page<AdminPaymentWebhookResponse> searchWebhooks(String processingStatus, String provider,
            Boolean checkMacValid, LocalDateTime from, LocalDateTime to, String keyword, Pageable pageable) {
        return webhooks.search(blankToNull(processingStatus), blankToNull(provider), checkMacValid, from, to,
                like(keyword), pageable).map(AdminPaymentWebhookResponse::from);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String like(String keyword) {
        return (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim().toLowerCase() + "%";
    }
}
