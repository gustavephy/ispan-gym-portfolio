package tw.com.ispan.gym.service.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import tw.com.ispan.gym.exception.InvalidRequestException;

/**
 * 圖片白名單驗證：不管後面接的是 Cloudinary 還是本地 fallback，驗證都在儲存之前執行，
 * 所以不需要任何外部連線就能測。雲端與本地存檔路徑本身依賴外部環境
 *（Cloudinary 金鑰、檔案系統工作目錄），由啟動實測驗證，不在此列。
 */
class FileServiceTest {

    /** 驗證失敗會在碰到 Cloudinary 之前就拋例外，這裡刻意不給 client */
    private final FileService service = new FileService(null);

    @Test
    void 空檔案被擋下() {
        assertThatThrownBy(() -> service.saveImage(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void MIME型別不在白名單被擋下() {
        assertThatThrownBy(() -> service.saveImage(new MockMultipartFile("file", "a.jpg", "text/plain", new byte[] { 1 })))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("不支援的檔案格式");
    }

    @Test
    void 副檔名不在白名單被擋下() {
        assertThatThrownBy(() -> service.saveImage(new MockMultipartFile("file", "a.exe", "image/jpeg", new byte[] { 1 })))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("副檔名");
    }
}
