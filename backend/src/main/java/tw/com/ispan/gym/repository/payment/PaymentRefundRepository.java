package tw.com.ispan.gym.repository.payment;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.dto.report.ReportRefundSummary;
import tw.com.ispan.gym.entity.payment.PaymentRefund;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Integer> {
    List<PaymentRefund> findByPaymentId(Integer paymentId);
    List<PaymentRefund> findByStatusOrderByRequestedAtAsc(String status);
    boolean existsByPaymentIdAndStatus(Integer paymentId, String status);
    @Query("SELECT COUNT(r) > 0 FROM PaymentRefund r WHERE r.payment.order.orderId = :orderId AND r.status = :status")
    boolean existsByPaymentOrderIdAndStatus(@Param("orderId") Integer orderId, @Param("status") String status);

    /**
     * 後台退款紀錄搜尋（分頁，全部狀態），條件皆選填。
     * keyword 需由呼叫端先轉小寫並包 {@code %}，比對訂單特店編號／退款單號。
     */
    @Query("""
            SELECT r FROM PaymentRefund r JOIN r.payment p JOIN p.order o
            WHERE (:status IS NULL OR r.status = :status)
              AND (:from IS NULL OR r.requestedAt >= :from)
              AND (:to IS NULL OR r.requestedAt < :to)
              AND (:keyword IS NULL
                   OR LOWER(o.merchantTradeNo) LIKE :keyword
                   OR LOWER(r.refundNo) LIKE :keyword)
            """)
    Page<PaymentRefund> search(@Param("status") String status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("keyword") String keyword, Pageable pageable);

    /** 期間完成退款彙總（報表用）；時間軸為 completedAt（退款生效日） */
    @Query("""
            SELECT new tw.com.ispan.gym.dto.report.ReportRefundSummary(COUNT(r), SUM(r.amount))
            FROM PaymentRefund r
            WHERE r.status = 'COMPLETED'
              AND r.completedAt >= :from AND r.completedAt < :to
            """)
    ReportRefundSummary summarizeCompleted(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
