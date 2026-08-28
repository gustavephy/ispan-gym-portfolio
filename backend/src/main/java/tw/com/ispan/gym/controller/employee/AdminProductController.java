package tw.com.ispan.gym.controller.employee;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.enums.product.ProductStatus;
import tw.com.ispan.gym.service.file.FileService;
import tw.com.ispan.gym.service.product.ProductService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminProductController {

    private final ProductService productService;
    private final FileService fileService;

    /**
     * 後台商品列表：不限狀態，已下架的商品也會回傳。
     *
     * <p>後台先前是直接打前台的 {@code GET /api/products} 取列表，但那支在 ProductService
     * 裡固定只查 ACTIVE，導致管理頁的「已下架商品」篩選永遠是空的、下架後的商品也無從檢視。
     *
     * @param status 傳入時只回該狀態；不傳代表全部（上架中與已下架都要）
     */
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> getProducts(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Integer productCategoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int pageSize) {
        log.info("Request to get admin products: status={}, categoryId={}, keyword={}, page={}, size={}",
                status, productCategoryId, keyword, page, pageSize);
        return ResponseEntity.ok(
                productService.getProductsForAdmin(status, productCategoryId, keyword, page, pageSize));
    }

    // 新增商品
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        log.info("Request to create product: {}", request.name());
        return ResponseEntity.ok(productService.createProduct(request));
    }

    // 上傳商品圖片
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("Request to upload product image: name={}", file.getOriginalFilename());
        String fileUrl = fileService.saveImage(file);
        return ResponseEntity.ok(Map.of("url", fileUrl));
    }

    // 修改商品
    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Integer productId, @Valid @RequestBody ProductRequest request) {
        log.info("Request to update product: id={}, name={}", productId, request.name());
        return ResponseEntity.ok(productService.updateProduct(productId, request));
    }

    // 下架商品
    @DeleteMapping("/{productId}")
    public ResponseEntity<String> deleteProduct(@PathVariable Integer productId) {
        log.info("Request to delete (deactivate) product: id={}", productId);
        productService.deleteProduct(productId);
        return ResponseEntity.ok("商品已下架");
    }
}
