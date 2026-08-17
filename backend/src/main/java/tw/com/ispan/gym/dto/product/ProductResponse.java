package tw.com.ispan.gym.dto.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.product.ProductStatus;

public record ProductResponse(
        Integer productId,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        ProductStatus status,
        String imageUrl,
        Integer categoryId,
        String categoryName,
        LocalDateTime createdAt,
        String brand,
        Double weight,
        String flavor,
        String size,
        String color,
        String material,
        String origin
) {
    public static ProductResponse from(Product product) {
        if (product == null) {
            return null;
        }
        Integer categoryId = product.getProductCategory() != null ? product.getProductCategory().getProductCategoryId() : null;
        String categoryName = product.getProductCategory() != null ? product.getProductCategory().getName() : null;

        return new ProductResponse(
                product.getProductId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus(),
                product.getImageUrl(),
                categoryId,
                categoryName,
                product.getCreatedAt(),
                product.getBrand(),
                product.getWeight(),
                product.getFlavor(),
                product.getSize(),
                product.getColor(),
                product.getMaterial(),
                product.getOrigin()
        );
    }
}
