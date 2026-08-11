package tw.com.ispan.gym.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DirectCheckoutRequest(
        @NotNull Integer productId,
        @NotNull @Positive Long quantity,
        @Size(max = 30) String couponCode) {}
