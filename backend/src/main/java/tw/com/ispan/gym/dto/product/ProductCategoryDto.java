package tw.com.ispan.gym.dto.product;

import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;

public record ProductCategoryDto(
        Integer productCategoryId,
        String name,
        Integer sortOrder,
        CategoryStatus status
) {
    public static ProductCategoryDto from(ProductCategory category) {
        if (category == null) {
            return null;
        }
        return new ProductCategoryDto(
                category.getProductCategoryId(),
                category.getName(),
                category.getSortOrder(),
                category.getStatus() != null ? category.getStatus() : CategoryStatus.ACTIVE
        );
    }
}
