package tw.com.ispan.gym.dto.subscription;

import java.time.LocalDateTime;

import lombok.Data;
import tw.com.ispan.gym.entity.MemberSubscription;
import tw.com.ispan.gym.enums.SubscriptionStatus;

@Data
public class SubscriptionResponse {
    private Integer subscriptionId;
    private String planName;
    private Integer subscriptionPlanId;
    private Long orderId;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private SubscriptionStatus status;
    private LocalDateTime createdAt;
    private Boolean isActive;
	
    public static SubscriptionResponse from(MemberSubscription sub) {
        SubscriptionResponse dto = new SubscriptionResponse();
        dto.setSubscriptionId(sub.getSubscriptionId());
        dto.setPlanName(sub.getPlanName());
        dto.setSubscriptionPlanId(sub.getSubscriptionPlan() != null ? sub.getSubscriptionPlan().getSubscriptionPlanId() : null);
        dto.setOrderId(sub.getOrder() != null ? sub.getOrder().getId() : null);
        dto.setStartDate(sub.getStartDate());
        dto.setEndDate(sub.getEndDate());
        dto.setStatus(sub.getStatus());
        dto.setCreatedAt(sub.getCreatedAt());
        dto.setIsActive(sub.getStatus() == SubscriptionStatus.ACTIVE
                && sub.getEndDate() != null
                && sub.getEndDate().isAfter(LocalDateTime.now()));
        return dto;
    }
}
