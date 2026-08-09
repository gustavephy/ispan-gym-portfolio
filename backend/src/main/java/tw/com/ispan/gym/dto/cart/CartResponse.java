package tw.com.ispan.gym.dto.cart;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(List<CartItemResponse> items, BigDecimal totalAmount) {}

