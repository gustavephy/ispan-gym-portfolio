package tw.com.ispan.gym.exception.aihealthchat;

import org.springframework.http.HttpStatus;

import tw.com.ispan.gym.exception.ApiException;

/**
 * 涵蓋「plan_content 不是合法 JSON」與「OpenPDF 繪製過程拋例外」兩種情境，
 * 對前端而言都是同一種制式訊息，不暴露排版/解析內部細節。
 */
public class PdfGenerationException extends ApiException {
    public PdfGenerationException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_GENERATION_FAILED", "課表 PDF 產生失敗，請稍後再試");
    }

    /** 供課表以外的 PDF 產生情境（如營運報表）帶入對應的使用者訊息，錯誤碼共用 */
    public PdfGenerationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_GENERATION_FAILED", message);
    }
}
