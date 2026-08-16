package tw.com.ispan.gym.repository.subscription;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Integer> {
	Optional<SubscriptionPlan> findByName(String name);

	List<SubscriptionPlan> findByStatus(String status);
}
