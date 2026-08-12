package tw.com.ispan.gym.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductCategoryDto;
import tw.com.ispan.gym.entity.ProductCategory;
import tw.com.ispan.gym.enums.CategoryStatus;
import tw.com.ispan.gym.repository.ProductCategoryRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductCategoryService {
	private final ProductCategoryRepository productCategoryRepository;
	
	//查詢所有商品類別
	public List<ProductCategoryDto> getAllProductCategories(){
		log.info("Fetching all product categories");
		return productCategoryRepository.findAll().stream()
				.map(entity-> ProductCategoryDto.from(entity))
				.collect(Collectors.toList());
	}
	
    public ProductCategoryDto createProductCategory(ProductCategoryDto dto) {
        log.info("Creating product category: name={}", dto.getName());
        CategoryStatus status = dto.getStatus() != null ? dto.getStatus() : CategoryStatus.ACTIVE;
        int sortOrder = dto.getSortOrder() != null ? dto.getSortOrder() : 0;

        ProductCategory cat = new ProductCategory();
        cat.setName(dto.getName());
        cat.setSortOrder(sortOrder);
        cat.setStatus(status);
        return ProductCategoryDto.from(productCategoryRepository.save(cat));
    }

    public void deleteProductCategory(Integer id) {
        log.info("Deleting product category: id={}", id);
        productCategoryRepository.deleteById(id);
    }

    public boolean existsById(Integer id) {
        return productCategoryRepository.existsById(id);
    }
	
}
