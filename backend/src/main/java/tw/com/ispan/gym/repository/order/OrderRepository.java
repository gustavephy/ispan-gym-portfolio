package tw.com.ispan.gym.repository.order;

import tw.com.ispan.gym.entity.order.Order;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByMemberIdOrderByCreatedAtDesc(Integer userId);
    Optional<Order> findByIdAndMemberId(Long id, Integer userId);
    Optional<Order> findByMerchantTradeNo(String merchantTradeNo);
    Optional<Order> findByTradeNo(String tradeNo);

    @Query("SELECT o.status, COUNT(o), SUM(o.totalAmount) FROM Order o "
            + "WHERE o.createdAt BETWEEN :from AND :to GROUP BY o.status")
    List<Object[]> aggregateByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o.paymentMethod, COUNT(o) FROM Order o "
            + "WHERE o.createdAt BETWEEN :from AND :to AND o.paymentMethod IS NOT NULL GROUP BY o.paymentMethod")
    List<Object[]> aggregateByPaymentMethod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}

