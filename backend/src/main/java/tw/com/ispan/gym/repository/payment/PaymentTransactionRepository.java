package tw.com.ispan.gym.repository.payment;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Integer> {
    @Query("SELECT t FROM PaymentTransaction t WHERE t.order.orderId = :orderId")
    Optional<PaymentTransaction> findByOrderId(@Param("orderId") Integer orderId);
    Optional<PaymentTransaction> findByMerchantTradeNo(String merchantTradeNo);

    /**
     * 後台交易紀錄搜尋（分頁），條件皆選填（傳 null 不篩）。
     * keyword 需由呼叫端先轉小寫並包上 {@code %}，比對特店交易編號／綠界交易編號／訂單編號。
     */
    @Query("""
            SELECT t FROM PaymentTransaction t JOIN t.order o
            WHERE (:status IS NULL OR t.status = :status)
              AND (:provider IS NULL OR t.provider = :provider)
              AND (:from IS NULL OR t.createdAt >= :from)
              AND (:to IS NULL OR t.createdAt < :to)
              AND (:keyword IS NULL
                   OR LOWER(t.merchantTradeNo) LIKE :keyword
                   OR LOWER(t.tradeNo) LIKE :keyword
                   OR LOWER(o.orderNo) LIKE :keyword)
            """)
    Page<PaymentTransaction> search(@Param("status") String status, @Param("provider") String provider,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("keyword") String keyword, Pageable pageable);
}
