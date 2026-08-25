package tw.com.ispan.gym.controller.employee;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import tw.com.ispan.gym.exception.FileUploadException;
import tw.com.ispan.gym.service.product.ProductService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminProductController {

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
            throw new FileUploadException("檔案上傳失敗，請確保檔案格式正確且大小合理");
        }
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
