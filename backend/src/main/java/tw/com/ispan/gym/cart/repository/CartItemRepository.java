package tw.com.ispan.gym.cart.repository;

import tw.com.ispan.gym.cart.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findAllByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<CartItem> findByIdAndUserId(Long id, Long userId);
    Optional<CartItem> findByUserIdAndProductId(Long userId, Integer productId);
    long deleteAllByUserId(Long userId);
}

