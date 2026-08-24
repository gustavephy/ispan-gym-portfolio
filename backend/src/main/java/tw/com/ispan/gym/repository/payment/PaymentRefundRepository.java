package tw.com.ispan.gym.repository.payment;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.dto.report.ReportRefundSummary;
import tw.com.ispan.gym.entity.payment.PaymentRefund;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Integer> {
    List<PaymentRefund> findByPaymentId(Integer paymentId);
    List<PaymentRefund> findByStatusOrderByRequestedAtAsc(String status);
    boolean existsByPaymentIdAndStatus(Integer paymentId, String status);

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
