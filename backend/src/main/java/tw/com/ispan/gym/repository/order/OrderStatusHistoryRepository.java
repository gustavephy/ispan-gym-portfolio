package tw.com.ispan.gym.repository.order;


import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Integer> {
}
