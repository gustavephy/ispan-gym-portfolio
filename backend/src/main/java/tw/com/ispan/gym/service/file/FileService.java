package tw.com.ispan.gym.service.file;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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

    /** 未設定 CLOUDINARY_* 環境變數時為空字串，此時退回本地 uploads/ 存檔，組員沒申請金鑰也能用上傳功能 */
    @Value("${app.cloudinary.cloud-name:}")
    private String cloudName;

    public FileService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    private boolean cloudinaryEnabled() {
        return cloudName != null && !cloudName.isBlank();
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

            // 沒有 Cloudinary 金鑰就走本地存檔備援（行為同上雲前的舊版）
            if (!cloudinaryEnabled()) {
                return saveToLocalDisk(fileBytes, publicId + ext);
            }

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
            log.error("Failed to save uploaded image", e);
            throw new FileUploadException("檔案儲存失敗，請重試");
        }
    }

    /**
     * 無 Cloudinary 金鑰時的本地存檔備援：MD5 檔名去重、回傳 /uploads/ 相對路徑，
     * 行為與上雲前的舊版一致。目錄解析與 {@link tw.com.ispan.gym.config.UploadedFileConfig}
     * 的優先序相同（先 cwd 的 uploads/，再上一層），寫進去的檔案才會是靜態路由對外提供的那份。
     */
    private UploadResult saveToLocalDisk(byte[] fileBytes, String filename) throws IOException {
        File uploadDir = new File("uploads");
        if (!uploadDir.exists() && new File("../uploads").exists()) {
            uploadDir = new File("../uploads");
        }
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        File dest = new File(uploadDir, filename);
        boolean isDuplicate = dest.exists();
        if (!isDuplicate) {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(fileBytes);
            }
            log.info("未設定 Cloudinary，圖片存到本地：{}", dest.getPath());
        } else {
            log.info("未設定 Cloudinary，本地已有相同圖片，直接沿用：{}", dest.getPath());
        }
        return new UploadResult("/uploads/" + filename, isDuplicate);
    }
}
