package tw.com.ispan.gym.order.repository;

import tw.com.ispan.gym.order.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findAllByOrderIdOrderByIdAsc(Long orderId);
}

