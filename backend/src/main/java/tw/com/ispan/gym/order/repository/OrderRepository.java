package tw.com.ispan.gym.order.repository;

import tw.com.ispan.gym.order.entity.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByUserIdOrderByCreatedAtDesc(Integer userId);
    Optional<Order> findByIdAndUserId(Long id, Integer userId);
    Optional<Order> findByMerchantTradeNo(String merchantTradeNo);
    Optional<Order> findByTradeNo(String tradeNo);
}

