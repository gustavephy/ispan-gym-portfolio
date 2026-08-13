package tw.com.ispan.gym.service.aihealthchat;

import tools.jackson.databind.ObjectMapper;

import tw.com.ispan.gym.ai.GeminiClient;
import tw.com.ispan.gym.dto.aiplan.AiHealthPlanResponse;
import tw.com.ispan.gym.dto.aiplan.GeneratePlanRequest;
import tw.com.ispan.gym.entity.aihealthchat.AiHealthPlan;
import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;
import tw.com.ispan.gym.exception.aihealthchat.AiServiceUnavailableException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenInbodyAccessException;
import tw.com.ispan.gym.exception.aihealthchat.InbodyRecordNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.InvalidGoalException;
import tw.com.ispan.gym.repository.aihealthchat.AiHealthPlanRepository;
import tw.com.ispan.gym.repository.aihealthchat.InbodyRecordRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiAdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AiAdvisorService.class);
    private static final Set<String> VALID_GOALS = Set.of("FAT_LOSS", "MUSCLE_GAIN", "MAINTAIN");

    private final InbodyRecordRepository inbodyRecords;
    private final AiHealthPlanRepository plans;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public AiAdvisorService(InbodyRecordRepository inbodyRecords, AiHealthPlanRepository plans,
            GeminiClient geminiClient, ObjectMapper objectMapper) {
        this.inbodyRecords = inbodyRecords;
        this.plans = plans;
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 刻意不整個包在 @Transactional 裡：讀 InBody、呼叫 AI（可能好幾秒）、寫入結果
     * 若共用同一個交易，會讓一個 DB 連線在等 AI 回應的整段時間都被占用。查詢與寫入
     * 各自用 Spring Data JPA repository 方法自帶的交易即可。
     */
    @Async("aiTaskExecutor")
    public CompletableFuture<AiHealthPlanResponse> generatePlan(Integer memberId, GeneratePlanRequest request) {
        validateGoal(request.goal());
        InbodyRecord record = ownedInbodyRecord(memberId, request.inbodyRecordId());
        String prompt = buildPrompt(record, request.goal());

        String rawText;
        try {
            rawText = geminiClient.generateContent(prompt);
        } catch (Exception ex) {
            log.error("呼叫 Gemini 失敗", ex);
            throw new AiServiceUnavailableException();
        }

        String planContentJson = extractJson(rawText);

        AiHealthPlan plan = AiHealthPlan.builder()
                .memberId(memberId)
                .inbodyRecordId(record.getId())
                .goal(request.goal())
                .planContent(planContentJson)
                .modelName(geminiClient.getModel())
                .build();
        AiHealthPlan saved = plans.save(plan);
        return CompletableFuture.completedFuture(toResponse(saved));
    }

    public List<AiHealthPlanResponse> getHistory(Integer memberId) {
        return plans.findByMemberIdOrderByCreatedAtDesc(memberId)
                .stream().map(this::toResponse).toList();
    }

    /**
     * IDOR 防護：inbodyRecordId 必須真的屬於目前這位會員，否則任何人猜到別人的
     * inbodyRecordId 就能用他人的量測資料產生課表（AiHealthPlan.java 的 entity
     * 註解已提醒過這個檢查屬於 Service 層職責）。
     */
    private InbodyRecord ownedInbodyRecord(Integer memberId, Integer inbodyRecordId) {
        InbodyRecord record = inbodyRecords.findByIdAndDeletedAtIsNull(inbodyRecordId)
                .orElseThrow(() -> new InbodyRecordNotFoundException(inbodyRecordId));
        if (!record.getMemberId().equals(memberId)) {
            throw new ForbiddenInbodyAccessException(inbodyRecordId);
        }
        return record;
    }

    private void validateGoal(String goal) {
        if (goal == null || !VALID_GOALS.contains(goal)) {
            throw new InvalidGoalException(goal);
        }
    }

    private String buildPrompt(InbodyRecord record, String goal) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位專業健身教練。請根據以下會員的 InBody 量測數據與訓練目標，")
                .append("生成一份為期一週（7 天，包含 1-2 天休息日）的健身課表與飲食建議。\n\n")
                .append("會員資料：\n");
        appendIfPresent(sb, "身高", record.getHeight(), "cm");
        appendIfPresent(sb, "體重", record.getWeight(), "kg");
        appendIfPresent(sb, "體脂率", record.getBodyFat(), "%");
        appendIfPresent(sb, "骨骼肌重", record.getMuscleMass(), "kg");
        appendIfPresent(sb, "BMI", record.getBmi(), "");
        sb.append("- 訓練目標：").append(describeGoal(goal)).append("\n\n");
        sb.append("請務必只回傳純 JSON，不要包含 markdown 標記或 JSON 以外的任何文字，格式如下：\n");
        sb.append("""
                {
                  "goal": "目標代碼",
                  "summary": "一段簡短總結，說明這份課表的重點方向",
                  "days": [
                    {
                      "day": 1,
                      "focus": "本日訓練部位",
                      "exercises": [
                        {"name": "動作名稱", "sets": 4, "reps": "8-10", "notes": "備註，可為空字串"}
                      ]
                    }
                  ],
                  "dietTips": ["飲食建議 1", "飲食建議 2"]
                }
                """);
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, BigDecimal value, String unit) {
        if (value != null) {
            sb.append("- ").append(label).append("：").append(value).append(unit).append("\n");
        }
    }

    private String describeGoal(String goal) {
        return switch (goal) {
            case "FAT_LOSS" -> "FAT_LOSS（減脂）";
            case "MUSCLE_GAIN" -> "MUSCLE_GAIN（增肌）";
            case "MAINTAIN" -> "MAINTAIN（維持現狀）";
            default -> goal;
        };
    }

    /**
     * JSON 容錯解析：Gemini 常把 JSON 包在 ```json ... ``` 這種 markdown code fence
     * 裡，也可能夾帶前後說明文字。這裡先去除 code fence，再抽出最外層的 {...} 區塊
     * 驗證是否為合法 JSON；失敗一律視為 AI 服務異常，不讓例外原樣往上炸穿。
     */
    private String extractJson(String rawText) {
        String candidate = stripCodeFence(rawText).trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            log.error("Gemini 回應找不到 JSON 區塊：{}", rawText);
            throw new AiServiceUnavailableException();
        }
        String jsonText = candidate.substring(start, end + 1);
        try {
            objectMapper.readTree(jsonText);
        } catch (Exception ex) {
            log.error("Gemini 回應不是合法 JSON：{}", rawText, ex);
            throw new AiServiceUnavailableException();
        }
        return jsonText;
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        trimmed = firstNewline == -1 ? trimmed : trimmed.substring(firstNewline + 1);
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }

    private AiHealthPlanResponse toResponse(AiHealthPlan plan) {
        return new AiHealthPlanResponse(plan.getId(), plan.getInbodyRecordId(), plan.getGoal(),
                plan.getPlanContent(), plan.getModelName(), plan.getCreatedAt());
    }
}
