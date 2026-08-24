package tw.com.ispan.gym.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 欄位驗證失敗的回應內容。
 *
 * <p>先前不論哪一條規則沒過，一律回「請確認輸入資料是否正確」——DTO 上寫好的
 * 「開始時間必須是未來時間」「時段長度需介於 60 分鐘至 90 分鐘之間」等訊息全被蓋掉，
 * 使用者看不出該改哪裡（實際踩到過：教練排班選了過去時間只得到那句籠統的話）。
 * 這裡固定住「訊息要帶出來」這件事，避免日後又被改回統一字串。
 */
class ValidationErrorResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MethodArgumentNotValidException exceptionWith(FieldError... fieldErrors) {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldErrors));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        return ex;
    }

    private FieldError fieldError(String field, String message) {
        return new FieldError("createCoachSlotRequest", field, message);
    }

    @Test
    void 驗證失敗以欄位自己的訊息回覆而不是籠統字串() {
        ResponseEntity<ApiErrorResponse> response = handler.invalidRequest(
                exceptionWith(fieldError("startAt", "開始時間必須是未來時間")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("開始時間必須是未來時間");
        assertThat(response.getBody().message()).isNotEqualTo("請確認輸入資料是否正確");
    }

    @Test
    void 多個欄位失敗時附上完整對應供表單逐欄標紅() {
        ResponseEntity<ApiErrorResponse> response = handler.invalidRequest(
                exceptionWith(fieldError("startAt", "開始時間不可為空"),
                        fieldError("endAt", "結束時間不可為空")));

        assertThat(response.getBody().errors())
                .containsEntry("startAt", "開始時間不可為空")
                .containsEntry("endAt", "結束時間不可為空");
    }

    @Test
    void 同一欄位多條規則同時失敗只保留第一則避免洗版() {
        ResponseEntity<ApiErrorResponse> response = handler.invalidRequest(
                exceptionWith(fieldError("startAt", "開始時間不可為空"),
                        fieldError("startAt", "開始時間必須是未來時間")));

        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors()).containsEntry("startAt", "開始時間不可為空");
    }

    @Test
    void 跨欄位規則的訊息同樣帶得出來() {
        // @AssertTrue 的 field 會是判斷方法的屬性名（durationValid），對不上實際輸入欄位，
        // 所以 message 以訊息本身為主，前端不需要解讀 field
        ResponseEntity<ApiErrorResponse> response = handler.invalidRequest(
                exceptionWith(fieldError("durationValid", "時段長度需介於 60 分鐘至 90 分鐘之間")));

        assertThat(response.getBody().message()).isEqualTo("時段長度需介於 60 分鐘至 90 分鐘之間");
    }

    @Test
    void 沒有欄位錯誤時退回原本的籠統訊息且不附errors() {
        ResponseEntity<ApiErrorResponse> response = handler.invalidRequest(exceptionWith());

        assertThat(response.getBody().message()).isEqualTo("請確認輸入資料是否正確");
        // errors 為 null 時 @JsonInclude(NON_NULL) 會整個略去，不讓回應多出空欄位
        assertThat(response.getBody().errors()).isNull();
    }
}
