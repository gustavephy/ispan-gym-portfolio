package tw.com.ispan.gym.service.aihealthchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import tw.com.ispan.gym.entity.aihealthchat.AiHealthPlan;
import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;
import tw.com.ispan.gym.exception.aihealthchat.AiHealthPlanNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenAiHealthPlanAccessException;
import tw.com.ispan.gym.exception.aihealthchat.PdfGenerationException;
import tw.com.ispan.gym.repository.aihealthchat.AiHealthPlanRepository;
import tw.com.ispan.gym.repository.aihealthchat.InbodyRecordRepository;

/**
 * 純 Mockito 單元測試（同 InbodyServiceTest 的做法，不連資料庫）。
 * PDF 內容本身不易斷言，成功案例驗證「回傳合法 PDF 位元組」（%PDF 檔頭與 %%EOF 結尾），
 * 重點放在 IDOR 防護與各種輸入狀況（非法 JSON、量測不足、欄位缺漏）不會炸出非預期例外。
 */
class PdfReportServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer PLAN_ID = 5;

    private static final String VALID_CONTENT = """
            {
              "summary": "以減脂為目標的四週課表",
              "days": [
                {"day": 1, "focus": "胸與三頭",
                 "exercises": [{"name": "槓鈴臥推", "sets": 4, "reps": "8-10", "notes": "最後一組力竭"}]},
                {"day": 2, "focus": "休息", "exercises": []}
              ],
              "dietTips": ["每日蛋白質 1.6g/kg", "睡前不吃精緻澱粉"]
            }
            """;

    private final AiHealthPlanRepository plans = mock(AiHealthPlanRepository.class);
    private final InbodyRecordRepository inbodyRecords = mock(InbodyRecordRepository.class);
    private final PdfReportService service = new PdfReportService(plans, inbodyRecords,
            JsonMapper.builder().build());

    private AiHealthPlan plan(Integer memberId, String content) {
        return AiHealthPlan.builder()
                .id(PLAN_ID)
                .memberId(memberId)
                .inbodyRecordId(10)
                .goal("FAT_LOSS")
                .planContent(content)
                .modelName("gemini-flash-latest")
                .createdAt(LocalDateTime.of(2026, 8, 14, 12, 0))
                .build();
    }

    private InbodyRecord record(LocalDate date, String weight, String bodyFat, String muscleMass) {
        return InbodyRecord.builder()
                .id(1)
                .memberId(MEMBER_ID)
                .updateDate(date)
                .weight(weight == null ? null : new BigDecimal(weight))
                .bodyFat(bodyFat == null ? null : new BigDecimal(bodyFat))
                .muscleMass(muscleMass == null ? null : new BigDecimal(muscleMass))
                .build();
    }

    private void assertIsPdf(byte[] bytes) {
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String tail = new String(bytes, Math.max(0, bytes.length - 32), Math.min(32, bytes.length),
                StandardCharsets.US_ASCII);
        assertThat(tail).contains("%%EOF");
    }

    // === IDOR 防護 ===========================================================

    @Test
    void 找不到課表拋NotFound() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generatePlanPdf(MEMBER_ID, PLAN_ID))
                .isInstanceOf(AiHealthPlanNotFoundException.class);
        verifyNoInteractions(inbodyRecords);
    }

    @Test
    void 下載他人課表拋Forbidden() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(999, VALID_CONTENT)));

        assertThatThrownBy(() -> service.generatePlanPdf(MEMBER_ID, PLAN_ID))
                .isInstanceOf(ForbiddenAiHealthPlanAccessException.class);
        verifyNoInteractions(inbodyRecords);
    }

    // === 內容解析 ============================================================

    @Test
    void 課表內容不是合法JSON拋PdfGenerationException() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, "這不是 JSON{{{")));

        assertThatThrownBy(() -> service.generatePlanPdf(MEMBER_ID, PLAN_ID))
                .isInstanceOf(PdfGenerationException.class);
    }

    // === 成功產生 PDF ========================================================

    @Test
    void 完整課表與InBody歷史產生合法PDF() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, VALID_CONTENT)));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of(
                        record(LocalDate.of(2026, 8, 12), "70.5", "22.1", "31.2"),
                        record(LocalDate.of(2026, 7, 12), "72.0", "23.4", "30.8"),
                        record(LocalDate.of(2026, 6, 12), "73.8", null, "30.1")));

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }

    @Test
    void 沒有InBody紀錄仍產生PDF不畫趨勢圖() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, VALID_CONTENT)));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of());

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }

    @Test
    void 只有一筆量測不足以畫趨勢圖仍產生PDF() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, VALID_CONTENT)));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of(record(LocalDate.of(2026, 8, 12), "70.5", "22.1", "31.2")));

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }

    @Test
    void 量測值全部相同仍產生PDF() {
        // drawSeriesChart 對 min==max 有人為拉開範圍的特例，確認不會除以零
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, VALID_CONTENT)));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of(
                        record(LocalDate.of(2026, 8, 12), "70.0", "22.0", "31.0"),
                        record(LocalDate.of(2026, 7, 12), "70.0", "22.0", "31.0")));

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }

    @Test
    void 課表JSON缺少days與dietTips仍產生PDF() {
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan(MEMBER_ID, "{\"summary\":\"只有總結\"}")));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of());

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }

    @Test
    void createdAt為null仍產生PDF() {
        AiHealthPlan noCreatedAt = plan(MEMBER_ID, VALID_CONTENT);
        noCreatedAt.setCreatedAt(null);
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(noCreatedAt));
        when(inbodyRecords.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of());

        assertIsPdf(service.generatePlanPdf(MEMBER_ID, PLAN_ID));
    }
}
