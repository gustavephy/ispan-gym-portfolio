package tw.com.ispan.gym.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull Integer productId,
        @NotNull @Min(1) Long quantity) {}

