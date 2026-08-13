package tw.com.ispan.gym.repository.cart;

import tw.com.ispan.gym.entity.cart.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findAllByMemberIdOrderByCreatedAtAsc(Integer userId);
    Optional<CartItem> findByIdAndMemberId(Long id, Integer userId);
    Optional<CartItem> findByMemberIdAndProductProductId(Integer userId, Integer productId);
    long deleteAllByMemberId(Integer userId);
}

