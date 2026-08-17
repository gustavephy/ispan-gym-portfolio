package tw.com.ispan.gym.service.product;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductCategoryDto;
import tw.com.ispan.gym.entity.product.ProductCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;
import tw.com.ispan.gym.repository.product.ProductCategoryRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductCategoryService {
    private final ProductCategoryRepository productCategoryRepository;

    // 查詢所有商品類別
    public List<ProductCategoryDto> getAllProductCategories() {
        log.info("Fetching all product categories");
        return productCategoryRepository.findAll().stream()
                .map(entity -> ProductCategoryDto.from(entity))
                .collect(Collectors.toList());
    }

    // 新增商品分類
    public ProductCategoryDto createProductCategory(ProductCategoryDto dto) {
        log.info("Creating product category: name={}", dto.name());
        CategoryStatus status = dto.status() != null ? dto.status() : CategoryStatus.ACTIVE;
        int sortOrder = dto.sortOrder() != null ? dto.sortOrder() : 0;

        ProductCategory cat = new ProductCategory();
        cat.setName(dto.name());
        cat.setSortOrder(sortOrder);
        cat.setStatus(status);
        return ProductCategoryDto.from(productCategoryRepository.save(cat));
    }

    // 更新商品分類
    public ProductCategoryDto updateProductCategory(Integer id, ProductCategoryDto dto) {
        log.info("Updating product category: id={}, name={}", id, dto.name());
        ProductCategory cat = productCategoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("找不到商品分類: " + id));

        if (dto.name() != null) {
            cat.setName(dto.name());
        }
        if (dto.sortOrder() != null) {
            cat.setSortOrder(dto.sortOrder());
        }
        if (dto.status() != null) {
            cat.setStatus(dto.status());
        }

        return ProductCategoryDto.from(productCategoryRepository.save(cat));
    }

    // 刪除商品分類
    public void deleteProductCategory(Integer id) {
        log.info("Deleting product category: id={}", id);
        productCategoryRepository.deleteById(id);
    }
}
