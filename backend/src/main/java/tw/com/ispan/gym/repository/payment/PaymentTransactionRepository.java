package tw.com.ispan.gym.repository.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.payment.PaymentTransaction;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Integer> {
    Optional<PaymentTransaction> findByOrderId(Integer orderId);
    Optional<PaymentTransaction> findByMerchantTradeNo(String merchantTradeNo);
}
