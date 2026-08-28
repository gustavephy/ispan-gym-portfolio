package tw.com.ispan.gym.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上傳超過 multipart 上限時要回 413 與明確代碼，而不是落到 Exception 兜底的 500「系統發生錯誤」——
 * 這個例外由 Tomcat 在進 controller 前拋出，走不到任何 service 層的檢查。
 */
class GlobalExceptionHandlerUploadTest {

    @Test
    void 檔案超過上限回413與FILE_TOO_LARGE() {
        ResponseEntity<ApiErrorResponse> res = new GlobalExceptionHandler()
                .handleMaxUploadSize(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("FILE_TOO_LARGE");
        assertThat(res.getBody().message()).contains("5MB");
    }
}
