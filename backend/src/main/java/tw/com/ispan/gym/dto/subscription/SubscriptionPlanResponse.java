package tw.com.ispan.gym.dto.subscription;

import java.math.BigDecimal;

import tw.com.ispan.gym.entity.subscription.SubscriptionPlan;

public record SubscriptionPlanResponse(
        Integer subscriptionPlanId,
        String name,
        String description,
        BigDecimal price,
        Integer durationDays,
        String status) {

    public static SubscriptionPlanResponse from(SubscriptionPlan plan) {
        return new SubscriptionPlanResponse(plan.getSubscriptionPlanId(), plan.getName(), plan.getDescription(),
                plan.getPrice(), plan.getDurationDays(), plan.getStatus());
    }
}
