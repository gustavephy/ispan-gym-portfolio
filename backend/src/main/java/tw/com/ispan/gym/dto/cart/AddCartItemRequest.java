package tw.com.ispan.gym.dto.cart;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull Integer productId,
        @NotNull @Min(1) Integer quantity) {}

