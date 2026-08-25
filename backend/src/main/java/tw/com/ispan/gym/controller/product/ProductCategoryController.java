package tw.com.ispan.gym.controller.product;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductCategoryDto;
import tw.com.ispan.gym.service.product.ProductCategoryService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProductCategoryController {

	private final ProductCategoryService productCategoryService;

	// 查詢商品分類
	@GetMapping("/api/product-categories")
	public ResponseEntity<List<ProductCategoryDto>> getProductCategories() {
		log.info("Request to get all product categories");
		return ResponseEntity.ok(productCategoryService.getAllProductCategories());
	}
}
