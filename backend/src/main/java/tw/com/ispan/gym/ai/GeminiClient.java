package tw.com.ispan.gym.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 包裝對 Gemini（Google AI Studio）generateContent 端點的呼叫。
 * 只負責「送出 prompt、拿回原始文字」，不做業務邏輯（prompt 組裝、JSON 容錯解析
 * 交給 AiAdvisorService），方便單獨替換供應商或單獨測試。
 */
@Component
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** 最多嘗試 3 次（1 次原始 + 2 次重試），只針對「重試大概率會成功」的暫時性錯誤 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

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
     * 由呼叫端（AiAdvisorService／ChatAiResponderService）決定要不要轉成制式錯誤訊息。
     *
     * <p>只對「重試大概率會自己好」的暫時性錯誤重試（Gemini 5xx、429 太多請求、
     * 網路逾時／連線失敗），400/401/403 這種請求本身有問題的錯誤重試沒有意義、
     * 直接失敗更快。實測遇過 Gemini 回 503「high demand」，過幾秒重試就正常了，
     * 這是官方文件本身就寫「暫時性，稍後再試」的情境。
     */
    public String generateContent(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            // 金鑰改為空預設（沒設 GOOGLE_API_KEY 也能啟動應用），呼叫當下才擋；
            // AiAdvisorService 會把這個例外轉成 503 AiServiceUnavailableException
            throw new IllegalStateException("未設定 GOOGLE_API_KEY，無法呼叫 Gemini");
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return callOnce(prompt);
            } catch (HttpServerErrorException | HttpClientErrorException.TooManyRequests
                    | ResourceAccessException ex) {
                lastError = ex;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                long delayMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 1s, 2s
                log.warn("呼叫 Gemini 失敗（第 {}/{} 次嘗試），{} 毫秒後重試：{}",
                        attempt, MAX_ATTEMPTS, delayMs, ex.getMessage());
                sleep(delayMs);
            }
        }
        throw lastError;
    }

    private String callOnce(String prompt) {
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重試等待期間被中斷", ex);
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Gemini 回應不是合法 JSON：" + body, ex);
        }
    }
}
