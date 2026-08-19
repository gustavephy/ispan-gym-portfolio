package tw.com.ispan.gym.service.aihealthchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tw.com.ispan.gym.ai.GeminiClient;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;
import tw.com.ispan.gym.entity.aihealthchat.ChatMessage.ChatCounterpartType;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.repository.aihealthchat.ChatMessageRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.course.PrivateCourseRepository;

/**
 * 「AI顧問」分頁所有需要呼叫 Gemini 的非同步邏輯：一般聊天回覆＋課程推薦。
 * 刻意獨立於 {@link ChatService} 之外（見 {@link ChatMessagePublisher} 的類別註解說明
 * 為什麼一定要拆開才能讓 @Async 生效），且刻意不放進 §2.2 的 AiAdvisorService——
 * 那邊是純課表生成，不查課程表；本類別才是規格書 §9.1 決策中「真實課程推薦」該待的地方。
 *
 * <p>課程推薦的防幻覺核心：Gemini 只能從查詢當下真實存在、狀態 ACTIVE 的課程清單裡選，
 * prompt 雖然已要求「只能從清單挑選」，但 prompt 本身不是保證——{@link #buildRecommendationMessage}
 * 會逐一核對 AI 回傳的課程名稱是否真的存在於剛查出的清單中，任何查無此課的名稱一律捨棄，
 * 這才是規格書 §2.4「確保推薦為真實可報名課程」真正落地的地方（§9.2 已定案的設計）。
 */
@Service
public class ChatAiResponderService {
    private static final Logger log = LoggerFactory.getLogger(ChatAiResponderService.class);
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String CHAT_FALLBACK_REPLY = "AI 顧問暫時無法回應，請稍後再試，或改用「教練」分頁聯繫真人教練。";
    private static final String RECOMMENDATION_FAILURE_REPLY = "課程推薦暫時無法使用，請稍後再試，或改用「教練」分頁請教練協助安排課程。";
    private static final String NO_ACTIVE_COURSE_REPLY = "目前本館沒有開放報名中的課程，建議直接洽詢教練或稍後再試。";
    private static final String NO_MATCHED_COURSE_REPLY = "目前沒有找到符合你需求的課程，建議直接洽詢教練安排最適合的課表。";

    private final ChatMessageRepository chatMessages;
    private final GroupCourseRepository groupCourses;
    private final PrivateCourseRepository privateCourses;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;
    private final ChatMessagePublisher publisher;

    public ChatAiResponderService(ChatMessageRepository chatMessages, GroupCourseRepository groupCourses,
            PrivateCourseRepository privateCourses, GeminiClient geminiClient, ObjectMapper objectMapper,
            ChatMessagePublisher publisher) {
        this.chatMessages = chatMessages;
        this.groupCourses = groupCourses;
        this.privateCourses = privateCourses;
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    /**
     * 一般 AI 顧問聊天回覆。呼叫 Gemini 失敗時捕捉例外、改推一則制式的失敗提示訊息，
     * 不可讓例外整個往上炸穿導致聊天室看起來像卡住沒回應（呼應 AiAdvisorService
     * 對外部 AI 呼叫的一貫容錯原則）。
     */
    @Async("aiTaskExecutor")
    public void generateChatReplyAsync(Integer memberId, String roomId) {
        String reply;
        try {
            List<ChatMessage> recentHistory = recentHistoryChronological(roomId);
            reply = truncate(geminiClient.generateContent(buildChatPrompt(recentHistory)));
        } catch (Exception ex) {
            log.error("AI 聊天回覆生成失敗，roomId={}", roomId, ex);
            reply = CHAT_FALLBACK_REPLY;
        }
        publisher.saveAndPush(aiMessage(memberId, reply));
    }

    /** 學員主動按「推薦課程」時觸發，僅從真實、目前開放報名的課程中挑選（不是每次聊天都查） */
    @Async("aiTaskExecutor")
    public void generateCourseRecommendationAsync(Integer memberId, String roomId) {
        // 用 *WithCategory 版本一次帶出 LAZY 的 category，因為下面組 prompt 時要讀
        // category 名稱，而這支方法離開後（Gemini 呼叫前後）session 就關閉了，見兩份
        // repository 對應方法的註解。
        List<GroupCourse> activeGroupCourses = groupCourses.findByStatusWithCategory(CourseStatus.ACTIVE);
        List<PrivateCourse> activePrivateCourses = privateCourses.findByStatusWithCategory(CourseStatus.ACTIVE);

        Set<String> allowedNames = new HashSet<>();
        activeGroupCourses.forEach(c -> allowedNames.add(c.getName()));
        activePrivateCourses.forEach(c -> allowedNames.add(c.getName()));

        String content;
        if (allowedNames.isEmpty()) {
            content = NO_ACTIVE_COURSE_REPLY;
        } else {
            try {
                List<ChatMessage> recentHistory = recentHistoryChronological(roomId);
                String prompt = buildCourseRecommendationPrompt(recentHistory, activeGroupCourses,
                        activePrivateCourses);
                JsonNode result = extractJson(geminiClient.generateContent(prompt));
                content = buildRecommendationMessage(result, allowedNames);
            } catch (Exception ex) {
                log.error("AI 課程推薦生成失敗，roomId={}", roomId, ex);
                content = RECOMMENDATION_FAILURE_REPLY;
            }
        }
        publisher.saveAndPush(aiMessage(memberId, content));
    }

    private ChatMessage aiMessage(Integer memberId, String content) {
        return ChatMessage.builder()
                .memberId(memberId)
                .counterpartType(ChatCounterpartType.AI)
                .senderIsMember(false)
                .messageContent(truncate(content))
                .build();
    }

    private List<ChatMessage> recentHistoryChronological(String roomId) {
        List<ChatMessage> recent = new ArrayList<>(chatMessages.findTop10ByRoomIdOrderByCreatedAtDesc(roomId));
        Collections.reverse(recent);
        return recent;
    }

    private String buildChatPrompt(List<ChatMessage> recentHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是健身房的 AI 健康顧問，個性親切但專業，只使用繁體中文回覆。")
                .append("請針對學員最新一則訊息給出簡潔的回覆（約 3-6 句），內容可涵蓋健身、飲食、InBody 數據解讀等主題；")
                .append("不確定或超出健身房服務範圍的問題，請坦白告知並建議洽詢教練或專業人員。")
                .append("不要使用 markdown 標記，不要加上任何身分前綴。\n\n");

        appendHistory(sb, recentHistory, recentHistory.size() - 1);

        if (!recentHistory.isEmpty()) {
            ChatMessage latest = recentHistory.get(recentHistory.size() - 1);
            sb.append("學員最新訊息：").append(latest.getMessageContent());
        }
        return sb.toString();
    }

    private String buildCourseRecommendationPrompt(List<ChatMessage> recentHistory,
            List<GroupCourse> activeGroupCourses, List<PrivateCourse> activePrivateCourses) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是健身房的 AI 健康顧問。請根據學員的對話內容與需求，從下面提供的「本館目前開放報名的真實課程清單」中，")
                .append("挑選最多 3 堂適合的課程推薦給學員。務必只能從清單中挑選，絕對不可以自己編造清單以外的課程名稱，")
                .append("也不可以更改清單中的課程名稱文字。\n\n");

        sb.append("團體課清單：\n");
        if (activeGroupCourses.isEmpty()) {
            sb.append("（目前無開放中的團體課）\n");
        } else {
            activeGroupCourses.forEach(c -> sb.append("- ").append(describeGroupCourse(c)).append("\n"));
        }

        sb.append("\n私教課清單：\n");
        if (activePrivateCourses.isEmpty()) {
            sb.append("（目前無開放中的私教課）\n");
        } else {
            activePrivateCourses.forEach(c -> sb.append("- ").append(describePrivateCourse(c)).append("\n"));
        }

        appendHistory(sb, recentHistory, recentHistory.size());

        sb.append("\n請務必只回傳純 JSON，不要包含 markdown 標記或 JSON 以外的任何文字，格式如下：\n");
        sb.append("""
                {
                  "intro": "一段簡短說明，介紹你為什麼推薦這些課程",
                  "courses": ["清單中的課程名稱1", "清單中的課程名稱2"]
                }
                """);
        return sb.toString();
    }

    private void appendHistory(StringBuilder sb, List<ChatMessage> recentHistory, int limit) {
        if (limit <= 0) {
            return;
        }
        sb.append("先前對話紀錄（由舊到新，供你判斷學員需求）：\n");
        for (int i = 0; i < limit; i++) {
            ChatMessage m = recentHistory.get(i);
            sb.append(m.isSenderIsMember() ? "學員" : "AI顧問").append("：").append(m.getMessageContent()).append("\n");
        }
        sb.append("\n");
    }

    private String describeGroupCourse(GroupCourse c) {
        String category = c.getCategory() != null ? c.getCategory().getName() : "未分類";
        String trial = Boolean.TRUE.equals(c.getIsTrial()) ? "，提供體驗課" : "";
        return c.getName() + "（團體課，分類：" + category + trial + "）";
    }

    private String describePrivateCourse(PrivateCourse c) {
        String category = c.getCategory() != null ? c.getCategory().getName() : "未分類";
        return c.getName() + "（私教課，分類：" + category + "，每單位 " + c.getSessionPerUnit() + " 堂，NT$" + c.getPrice() + "）";
    }

    /**
     * 防幻覺核心檢查：AI 回傳的每個課程名稱都要真的存在於剛查出的清單中，
     * 對不上的一律捨棄，不能只靠 prompt 要求 AI 誠實作答。
     */
    private String buildRecommendationMessage(JsonNode root, Set<String> allowedNames) {
        String intro = root.path("intro").asString("以下是為你挑選的課程：");
        List<String> recommended = new ArrayList<>();
        JsonNode coursesNode = root.path("courses");
        if (coursesNode.isArray()) {
            for (JsonNode courseNode : coursesNode) {
                String name = courseNode.asString(null);
                if (name != null && allowedNames.contains(name)) {
                    recommended.add(name);
                }
            }
        }
        if (recommended.isEmpty()) {
            return NO_MATCHED_COURSE_REPLY;
        }
        StringBuilder sb = new StringBuilder(intro).append("\n\n推薦課程：\n");
        recommended.forEach(name -> sb.append("• ").append(name).append("\n"));
        return sb.toString().trim();
    }

    /**
     * JSON 容錯解析：Gemini 常把 JSON 包在 ```json ... ``` 這種 markdown code fence
     * 裡，也可能夾帶前後說明文字，做法比照 AiAdvisorService 的同名邏輯。
     */
    private JsonNode extractJson(String rawText) {
        String candidate = stripCodeFence(rawText).trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new IllegalStateException("Gemini 回應找不到 JSON 區塊：" + rawText);
        }
        return objectMapper.readTree(candidate.substring(start, end + 1));
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

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > MAX_CONTENT_LENGTH ? trimmed.substring(0, MAX_CONTENT_LENGTH) : trimmed;
    }
}
