package tw.com.ispan.gym.controller.employee;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductCategoryDto;
import tw.com.ispan.gym.service.product.ProductCategoryService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/product-categories")
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminProductCategoryController {

    private final ProductCategoryService productCategoryService;

    // 新增商品分類
    @PostMapping
    public ResponseEntity<ProductCategoryDto> createProductCategory(@RequestBody ProductCategoryDto category) {
        log.info("Request to create product category: name={}", category.name());
        return ResponseEntity.ok(productCategoryService.createProductCategory(category));
    }

    // 更新商品分類
    @PutMapping("/{productCategoryId}")
    public ResponseEntity<ProductCategoryDto> updateProductCategory(
            @PathVariable Integer productCategoryId,
            @RequestBody ProductCategoryDto category) {
        log.info("Request to update product category: id={}", productCategoryId);
        return ResponseEntity.ok(productCategoryService.updateProductCategory(productCategoryId, category));
    }

    // 刪除商品分類
    @DeleteMapping("/{productCategoryId}")
    public ResponseEntity<String> deleteProductCategory(@PathVariable Integer productCategoryId) {
        log.info("Request to delete product category: id={}", productCategoryId);
        productCategoryService.deleteProductCategory(productCategoryId);
        return ResponseEntity.ok("商品分類已刪除");
    }
}
