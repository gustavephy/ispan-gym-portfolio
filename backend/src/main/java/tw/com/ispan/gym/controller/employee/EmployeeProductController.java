package tw.com.ispan.gym.controller.employee;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tw.com.ispan.gym.dto.product.ProductRequest;
import tw.com.ispan.gym.dto.product.ProductResponse;
import tw.com.ispan.gym.service.product.ProductService;
import tw.com.ispan.gym.exception.BusinessRuleException;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employee/products")
@Slf4j
@PreAuthorize("hasRole('EMPLOYEE')")
public class EmployeeProductController {

    private final ProductService productService;

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
        try {
            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            String originalName = file.getOriginalFilename();
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
            }

            byte[] fileBytes = file.getBytes();

            // 用 MD5 hash 當檔名，相同圖片只存一份
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String filename = sb.toString() + ext;
            File dest = new File(uploadDir, filename);

            // 若相同檔案已存在，直接回傳 URL，不重複寫入
            if (!dest.exists()) {
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(fileBytes);
                }
                log.info("Product image saved: {}", filename);
            } else {
                log.info("Duplicate image detected, reusing existing file: {}", filename);
            }

            String fileUrl = "/uploads/" + filename;
            log.info("Product image upload complete: {}", fileUrl);
            return ResponseEntity.ok(Map.of("url", fileUrl));
        } catch (Exception e) {
            log.error("Failed to upload product image", e);
            throw new BusinessRuleException("檔案上傳失敗：" + e.getMessage());
        }
    }

    // 修改商品
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Integer id, @Valid @RequestBody ProductRequest request) {
        log.info("Request to update product: id={}, name={}", id, request.name());
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    // 下架商品
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProduct(@PathVariable Integer id) {
        log.info("Request to delete (deactivate) product: id={}", id);
        productService.deleteProduct(id);
        return ResponseEntity.ok("商品已下架");
    }
}
