package tw.com.ispan.gym.dto.subscription;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;

public record SubscriptionResponse(
        Integer subscriptionId,
        String planName,
        Integer subscriptionPlanId,
        Integer orderId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        SubscriptionStatus status,
        LocalDateTime createdAt,
        Boolean isActive
) {
    public static SubscriptionResponse from(MemberSubscription sub) {
        if (sub == null) {
            return null;
        }
        Integer subscriptionPlanId = sub.getSubscriptionPlan() != null ? sub.getSubscriptionPlan().getSubscriptionPlanId() : null;
        Integer orderId = sub.getOrder() != null ? sub.getOrder().getId() : null;
        boolean isActive = sub.getStatus() == SubscriptionStatus.ACTIVE
                && sub.getEndDate() != null
                && sub.getEndDate().isAfter(LocalDateTime.now());

        return new SubscriptionResponse(
                sub.getMemberSubscriptionId(),
                sub.getPlanName(),
                subscriptionPlanId,
                orderId,
                sub.getStartDate(),
                sub.getEndDate(),
                sub.getStatus(),
                sub.getCreatedAt(),
                isActive
        );
    }
}
