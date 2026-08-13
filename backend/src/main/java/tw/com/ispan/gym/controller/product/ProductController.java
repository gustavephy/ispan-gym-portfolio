package tw.com.ispan.gym.controller.product;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.service.product.ProductService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProductController {
	
	private final ProductService productService;
	
	//查詢商品列表
    @GetMapping("/api/products")
    public ResponseEntity<Page<ProductResponse>> getProducts(
             @RequestParam(required = false) Integer productCategoryId,
             @RequestParam(required = false) String keyword,
             @RequestParam(required = false) String brand,
             @RequestParam(required = false) String flavor,
             @RequestParam(name = "productSize", required = false) String size,
             @RequestParam(required = false) BigDecimal minPrice,
             @RequestParam(required = false) BigDecimal maxPrice,
             @RequestParam(defaultValue = "0") int page,
             @RequestParam(name = "size", defaultValue = "10") int pageSize) {
        log.info("Request to get products: productCategoryId={}, keyword={}, brand={}, flavor={}, size={}, minPrice={}, maxPrice={}, page={}, size={}",
            productCategoryId, keyword, brand, flavor, size, minPrice, maxPrice, page, pageSize);
        return ResponseEntity.ok(productService.getProducts(productCategoryId, keyword, brand, flavor, size, minPrice, maxPrice, page, pageSize));
    }
	
	//查詢單一商品詳情
	@GetMapping("/api/products/{productId}")
	public ResponseEntity<ProductResponse> getProduct(
			@PathVariable Integer productId){
		log.info("Request to get product detail: productId={}",productId);
		return ResponseEntity.ok(productService.getProductById(productId));
	}
	
	//新增商品(管理員)
	@PostMapping("/api/admin/products/upload")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<ProductResponse> createProduct (@Valid @RequestBody ProductRequest request){
		log.info("Request to create product:{}",request.getName());
		return ResponseEntity.ok(productService.createProduct(request));
	}
	
	//修改商品(管理員)
	@PutMapping("/api/admin/products/{productId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<ProductResponse> updateProduct(
			@PathVariable Integer productId,@Valid @RequestBody ProductRequest request){
		log.info("Request to update product: productId={}, name={}", productId, request.getName());
		return ResponseEntity.ok(productService.updateProduct(productId,request));
	}
	
	//下架商品
	@DeleteMapping("/api/admin/products/{productId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<String> deleteProduct(
			@PathVariable Integer productId){
		log.info("Request to delete (deactivate) product: productId={}",productId);
		productService.deleteProduct(productId);
		return ResponseEntity.ok("商品已下架");
	}
	
}
