package tw.com.ispan.gym.repository.payment;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;

public interface PaymentWebhookRepository extends JpaRepository<PaymentWebhook, Integer> {
    boolean existsByProviderAndEventKey(String provider, String eventKey);

    /**
     * 後台金流回呼搜尋（分頁），條件皆選填。排查卡住的付款時常用 processingStatus=ERROR 篩。
     * keyword 需由呼叫端先轉小寫並包 {@code %}，比對特店交易編號／綠界交易編號／event key。
     */
    @Query("""
            SELECT w FROM PaymentWebhook w
            WHERE (:processingStatus IS NULL OR w.processingStatus = :processingStatus)
              AND (:provider IS NULL OR w.provider = :provider)
              AND (:checkMacValid IS NULL OR w.checkMacValid = :checkMacValid)
              AND (:from IS NULL OR w.receivedAt >= :from)
              AND (:to IS NULL OR w.receivedAt < :to)
              AND (:keyword IS NULL
                   OR LOWER(w.merchantTradeNo) LIKE :keyword
                   OR LOWER(w.tradeNo) LIKE :keyword
                   OR LOWER(w.eventKey) LIKE :keyword)
            """)
    Page<PaymentWebhook> search(@Param("processingStatus") String processingStatus,
            @Param("provider") String provider, @Param("checkMacValid") Boolean checkMacValid,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("keyword") String keyword, Pageable pageable);
}
