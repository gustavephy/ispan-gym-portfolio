package tw.com.ispan.gym.repository.member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;

public interface MemberSubscriptionRepository extends JpaRepository<MemberSubscription, Integer> {
	List<MemberSubscription> findByMemberIdOrderByEndDateDesc(Integer memberId);

	Optional<MemberSubscription> findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(Integer memberId,
			LocalDateTime now, SubscriptionStatus status);
}
