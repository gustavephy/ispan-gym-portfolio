package tw.com.ispan.gym.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.payment.PaymentRefund;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {
    List<PaymentRefund> findByPaymentId(Long paymentId);
    List<PaymentRefund> findByStatusOrderByRequestedAtAsc(String status);
}
