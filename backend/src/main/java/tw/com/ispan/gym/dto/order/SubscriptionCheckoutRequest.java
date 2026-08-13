package tw.com.ispan.gym.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubscriptionCheckoutRequest(
        @NotNull Integer subscriptionPlanId,
        @Size(max = 30) String couponCode) {}
