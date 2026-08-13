package tw.com.ispan.gym.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByOrderId(Long orderId);
    Optional<PaymentTransaction> findByMerchantTradeNo(String merchantTradeNo);
}
