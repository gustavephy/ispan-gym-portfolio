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
@Slf4j
@RequestMapping("/api/employee/product-categories")
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeProductCategoryController {
    private final ProductCategoryService productCategoryService;

    // 新增商品分類
    @PostMapping
    public ResponseEntity<ProductCategoryDto> createProductCategory(@RequestBody ProductCategoryDto category) {
        log.info("request to create product category:{}", category.name());
        return ResponseEntity.ok(productCategoryService.createProductCategory(category));
    }

    // 更新商品分類
    @PutMapping("/{id}")
    public ResponseEntity<ProductCategoryDto> updateProductCategory(
            @PathVariable Integer id,
            @RequestBody ProductCategoryDto category) {
        log.info("request to update product category:{}", id);
        return ResponseEntity.ok(productCategoryService.updateProductCategory(id, category));
    }

    // 刪除商品分類
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProductCategory(@PathVariable Integer id) {
        log.info("request to delete product category:{}", id);
        productCategoryService.deleteProductCategory(id);
        return ResponseEntity.ok("商品分類已刪除");
    }
}
