package tw.com.ispan.gym.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DirectCheckoutRequest(
        @NotNull Integer privateCourseId,
        @NotNull @Positive Integer quantity,
        @Size(max = 30) String couponCode) {}
