package tw.com.ispan.gym.dto.product;

import lombok.Data;
import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;

@Data
public class ProductCategoryDto {
	private Integer productCategoryId;
	private String name;
	private Integer sortOrder;
	private CategoryStatus status;
	
	public static ProductCategoryDto from(ProductCategory category) {
		ProductCategoryDto dto = new ProductCategoryDto();
		dto.setProductCategoryId(category.getProductCategoryId());
		dto.setName(category.getName());
		dto.setSortOrder(category.getSortOrder());
		dto.setStatus(category.getStatus()!= null ? category.getStatus() : CategoryStatus.ACTIVE);
		return dto;
	}
}
