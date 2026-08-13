package tw.com.ispan.gym.repository.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.payment.PaymentWebhook;

public interface PaymentWebhookRepository extends JpaRepository<PaymentWebhook, Long> {
    boolean existsByProviderAndEventKey(String provider, String eventKey);
}
