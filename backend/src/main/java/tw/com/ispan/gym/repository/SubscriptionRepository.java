package tw.com.ispan.gym.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow.Subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.enums.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, Integer> {
	List<Subscription> findByMemberId(Integer memberId);

	Optional<Subscription> findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(Integer memberId,
			LocalDateTime now, SubscriptionStatus status);
}
