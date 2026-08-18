package tw.com.ispan.gym.repository.cart;

import tw.com.ispan.gym.entity.cart.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Integer> {
    List<CartItem> findAllByMemberIdOrderByCreatedAtAsc(Integer memberId);
    Optional<CartItem> findByIdAndMemberId(Integer id, Integer memberId);
    Optional<CartItem> findByMemberIdAndProductProductId(Integer memberId, Integer productId);
    long deleteAllByMemberId(Integer memberId);
}

