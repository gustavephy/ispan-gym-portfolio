package tw.com.ispan.gym.repository.order;


import tw.com.ispan.gym.entity.order.OrderStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Integer> {
    @Query("SELECT osh FROM OrderStatusHistory osh WHERE osh.order.orderId = :orderId ORDER BY osh.changedAt ASC")
    List<OrderStatusHistory> findAllByOrderIdOrderByChangedAtAsc(@Param("orderId") Integer orderId);
}
