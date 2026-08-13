package tw.com.ispan.gym.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 包裝對 Gemini（Google AI Studio）generateContent 端點的呼叫。
 * 只負責「送出 prompt、拿回原始文字」，不做業務邏輯（prompt 組裝、JSON 容錯解析
 * 交給 AiAdvisorService），方便單獨替換供應商或單獨測試。
 */
@Component
public class GeminiClient {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiClient(RestTemplate restTemplate, ObjectMapper objectMapper,
            @Value("${app.ai.gemini.api-key}") String apiKey,
            @Value("${app.ai.gemini.model}") String model,
            @Value("${app.ai.gemini.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    /**
     * 呼叫 generateContent，回傳模型產生的原始文字。
     * 失敗時（HTTP 非 2xx、回應格式不如預期）直接丟出未經包裝的例外，
     * 由呼叫端（AiAdvisorService）決定要不要轉成制式錯誤訊息。
     */
    public String generateContent(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            // 金鑰改為空預設（沒設 GOOGLE_API_KEY 也能啟動應用），呼叫當下才擋；
            // AiAdvisorService 會把這個例外轉成 503 AiServiceUnavailableException
            throw new IllegalStateException("未設定 GOOGLE_API_KEY，無法呼叫 Gemini");
        }
        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        ObjectNode requestBody = objectMapper.createObjectNode();
        ArrayNode contents = requestBody.putArray("contents");
        ArrayNode parts = contents.addObject().putArray("parts");
        parts.addObject().put("text", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        JsonNode root = parseJson(response.getBody());
        JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
        if (textNode.isMissingNode()) {
            throw new IllegalStateException("Gemini 回應缺少預期欄位 candidates[0].content.parts[0].text：" + response.getBody());
        }
        return textNode.asText();
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Gemini 回應不是合法 JSON：" + body, ex);
        }
    }
}
