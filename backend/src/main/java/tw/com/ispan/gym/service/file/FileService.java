package tw.com.ispan.gym.service.file;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import tw.com.ispan.gym.exception.FileUploadException;
import tw.com.ispan.gym.exception.InvalidRequestException;

import tw.com.ispan.gym.dto.file.UploadResult;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif");
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif");

    private final Cloudinary cloudinary;

    public FileService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public UploadResult saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("上傳檔案不可為空");
        }

        // 1. 驗證 MIME 類型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidRequestException("不支援的檔案格式，僅允許上傳 JPG、PNG、GIF、WEBP 圖片");
        }

        // 2. 驗證副檔名
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        }
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new InvalidRequestException("不支援的副檔名，僅允許上傳 JPG、PNG、GIF、WEBP 圖片");
        }

        try {
            byte[] fileBytes = file.getBytes();

            // 3. 計算 MD5 Hash
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String publicId = sb.toString();

            // 4. 檢查 Cloudinary 內是否已存在相同的 public_id
            boolean isDuplicate = false;
            String secureUrl = null;
            try {
                Map<?, ?> resource = cloudinary.api().resource(publicId, ObjectUtils.emptyMap());
                if (resource != null && resource.containsKey("secure_url")) {
                    secureUrl = (String) resource.get("secure_url");
                    isDuplicate = true;
                    log.info("Duplicate image detected on Cloudinary, reusing: {}", secureUrl);
                }
            } catch (com.cloudinary.api.exceptions.NotFound e) {
                // 不存在，將進行上傳
            } catch (Exception e) {
                log.warn("Failed to check duplicate on Cloudinary, proceeding to upload", e);
            }

            // 5. 如果不存在，上傳至 Cloudinary
            if (!isDuplicate) {
                Map<?, ?> uploadResult = cloudinary.uploader().upload(fileBytes, ObjectUtils.asMap(
                        "public_id", publicId,
                        "overwrite", true
                ));
                secureUrl = (String) uploadResult.get("secure_url");
                log.info("Image uploaded successfully to Cloudinary: {}", secureUrl);
            }

            return new UploadResult(secureUrl, isDuplicate);
        } catch (Exception e) {
            log.error("Failed to save uploaded image to Cloudinary", e);
            throw new FileUploadException("檔案上傳至雲端失敗，請重試");
        }
    }
}
