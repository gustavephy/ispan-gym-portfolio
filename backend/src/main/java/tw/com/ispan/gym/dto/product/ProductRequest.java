package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductRequest(
                @NotBlank(message = "商品名稱不得為空") String name,
                String description,
                @NotNull(message = "價格不得為空") @DecimalMin(value = "0.01", message = "價格必須大於 0") BigDecimal price,
                @NotNull(message = "庫存不得為空") @Min(value = 0, message = "庫存不得為負數") Integer stock,
                String imageUrl,
                Integer productCategoryId,
                String brand,
                Double weight,
                String flavor,
                String size,
                String color,
                String material,
                String origin) {
}
