package tw.com.ispan.gym.controller;

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
import tw.com.ispan.gym.enums.ProductType;
import tw.com.ispan.gym.service.ProductService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProductController {
	
	private final ProductService productService;
	
	//查詢商品列表
	@GetMapping("/api/products")
	public ResponseEntity<Page<ProductResponse>> getProducts(
			@RequestParam(required = false) ProductType type,
			@RequestParam(required = false) Integer categoryId,
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size){
		log.info("Request to get products with type={}, categoryId={}, keyword={}, page={}, size={}", type, categoryId, keyword, page, size);
		return ResponseEntity.ok(productService.getProducts(type,categoryId,keyword,page,size));
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
	
	//獲取訂閱方的id
	@GetMapping("/api/products/subscription-product-id")
	public ResponseEntity<Integer> getSubscriptionProductId(){
		log.info("Request to get subscription product id");
		return ResponseEntity.ok(productService.getSubscriptionProductId());
	}
}
