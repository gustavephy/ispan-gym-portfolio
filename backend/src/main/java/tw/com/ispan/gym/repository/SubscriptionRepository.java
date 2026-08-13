package tw.com.ispan.gym.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.subscription.MemberSubscription;
import tw.com.ispan.gym.enums.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<MemberSubscription, Integer> {
	List<MemberSubscription> findByMemberId(Integer memberId);

	Optional<MemberSubscription> findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(Integer memberId,
			LocalDateTime now, SubscriptionStatus status);
}
