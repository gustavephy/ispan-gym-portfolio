package tw.com.ispan.gym.repository.order;


import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Integer> {
    List<OrderStatusHistory> findAllByOrderIdOrderByChangedAtAsc(Integer orderId);
}
