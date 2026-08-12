package tw.com.ispan.gym.repository;

import tw.com.ispan.gym.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findAllByMemberMemberIdOrderByCreatedAtAsc(Integer userId);
    Optional<CartItem> findByIdAndMemberMemberId(Long id, Integer userId);
    Optional<CartItem> findByMemberMemberIdAndProductProductId(Integer userId, Integer productId);
    long deleteAllByMemberMemberId(Integer userId);
}

