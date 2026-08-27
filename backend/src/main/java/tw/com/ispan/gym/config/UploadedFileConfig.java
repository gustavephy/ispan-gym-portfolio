package tw.com.ispan.gym.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把後台上傳的圖片開放成靜態資源。
 *
 * <p>AdminProductController#uploadImage 與課程的上傳端點都把檔案寫進專案根目錄下的
 * {@code uploads/}，並回傳 {@code /uploads/<檔名>} 這種相對路徑存進 image_url。
 * 但那個目錄不在 classpath 裡，Spring 預設不會對外提供，沒有這段對應設定的話
 * 前台拿到網址也只會得到 404／401，圖片一律顯示不出來。
 *
 * <p>存取權限另外在 SecurityConfig 以 {@code GET /uploads/**} permitAll 開放——
 * 商品圖在未登入的前台商城也要看得到；寫入端仍受 {@code /api/admin/**} 保護。
 */
@Configuration
public class UploadedFileConfig implements WebMvcConfigurer {

    /** 與上傳端點寫檔時使用的相對路徑一致（new File("uploads")） */
    private static final String UPLOAD_DIR = "uploads";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath.toUri().toString());
    }
}
