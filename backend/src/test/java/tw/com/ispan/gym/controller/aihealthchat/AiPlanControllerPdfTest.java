package tw.com.ispan.gym.controller.aihealthchat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.aihealthchat.AiHealthPlanNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenAiHealthPlanAccessException;
import tw.com.ispan.gym.exception.aihealthchat.PdfGenerationException;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.aihealthchat.AiAdvisorService;
import tw.com.ispan.gym.service.aihealthchat.PdfReportService;

/**
 * GET /api/ai-plans/{id}/pdf 的 controller 層測試：standalone MockMvc，
 * service 全 mock，只驗證路由、回應標頭與 ApiException → JSON 錯誤格式的對應；
 * PDF 內容正確性由 PdfReportServiceTest 負責，JWT 保護由 JwtSecuritySmokeTest 統一驗證。
 */
class AiPlanControllerPdfTest {

    private static final Integer MEMBER_ID = 1;
    private static final byte[] FAKE_PDF = "%PDF-fake".getBytes();

    private final AiAdvisorService aiAdvisorService = mock(AiAdvisorService.class);
    private final PdfReportService pdfReportService = mock(PdfReportService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AiPlanController(aiAdvisorService, pdfReportService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 下載成功回傳PDF與附件標頭() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(pdfReportService.generatePlanPdf(MEMBER_ID, 5)).thenReturn(FAKE_PDF);

        mockMvc.perform(get("/api/ai-plans/5/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ai-health-plan-5.pdf\""))
                .andExpect(content().bytes(FAKE_PDF));
    }

    @Test
    void 課表不存在回404與錯誤代碼() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(pdfReportService.generatePlanPdf(MEMBER_ID, 99))
                .thenThrow(new AiHealthPlanNotFoundException(99));

        mockMvc.perform(get("/api/ai-plans/99/pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_HEALTH_PLAN_NOT_FOUND"));
    }

    @Test
    void 他人課表回403與錯誤代碼() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(pdfReportService.generatePlanPdf(MEMBER_ID, 7))
                .thenThrow(new ForbiddenAiHealthPlanAccessException(7));

        mockMvc.perform(get("/api/ai-plans/7/pdf"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_AI_HEALTH_PLAN_ACCESS"));
    }

    @Test
    void 產生失敗回500與制式訊息() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(pdfReportService.generatePlanPdf(MEMBER_ID, 5))
                .thenThrow(new PdfGenerationException());

        mockMvc.perform(get("/api/ai-plans/5/pdf"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PDF_GENERATION_FAILED"));
    }
}
