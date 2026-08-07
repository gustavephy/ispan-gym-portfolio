package tw.com.ispan.gym.repository;

import tw.com.ispan.gym.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findAllByUserIdOrderByCreatedAtAsc(Integer userId);
    Optional<CartItem> findByIdAndUserId(Long id, Integer userId);
    Optional<CartItem> findByUserIdAndProductId(Integer userId, Integer productId);
    long deleteAllByUserId(Integer userId);
}

