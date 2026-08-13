package tw.com.ispan.gym.repository.order;

import tw.com.ispan.gym.entity.order.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByMemberIdOrderByCreatedAtDesc(Integer userId);
    Optional<Order> findByIdAndMemberId(Long id, Integer userId);
    Optional<Order> findByMerchantTradeNo(String merchantTradeNo);
    Optional<Order> findByTradeNo(String tradeNo);
}

