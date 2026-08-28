package tw.com.ispan.gym.service.file;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tw.com.ispan.gym.exception.FileUploadException;
import tw.com.ispan.gym.exception.InvalidRequestException;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    public String saveImage(MultipartFile file) {
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
            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            byte[] fileBytes = file.getBytes();

            // 3. 計算 MD5 Hash 作為檔名，重複檔案不重複存入
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String filename = sb.toString() + ext;
            File dest = new File(uploadDir, filename);

            if (!dest.exists()) {
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(fileBytes);
                }
                log.info("Image file saved successfully: {}", filename);
            } else {
                log.info("Duplicate image detected, reusing existing file: {}", filename);
            }

            return "/uploads/" + filename;
        } catch (Exception e) {
            log.error("Failed to save uploaded image", e);
            throw new FileUploadException("檔案儲存失敗，請重試");
        }
    }
}
