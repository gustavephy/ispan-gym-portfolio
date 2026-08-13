package tw.com.ispan.gym.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 獨立驗證 Gemini API 金鑰與呼叫是否正常：純 java.net.http.HttpClient 直接打 REST
 * 端點，不牽涉 Spring context、AiAdvisorService 或任何業務邏輯，出錯時容易定位是
 * 金鑰/額度/網路問題，還是之後接上 prompt 組裝邏輯才出的問題。
 *
 * 需要環境變數 GOOGLE_API_KEY 才會執行；沒有設定時自動略過，不會讓其他人（或 CI）
 * 沒有金鑰時測試失敗。
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class GeminiApiConnectivityTest {

    private static final String MODEL = "gemini-flash-latest";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 呼叫Gemini生成內容應該成功() throws Exception {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL
                + ":generateContent?key=" + apiKey;

        String requestBody = """
                {
                  "contents": [{
                    "parts": [{"text": "請用一句話回答：1加1等於多少？"}]
                  }]
                }
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("HTTP status: " + response.statusCode());
        System.out.println("Response body: " + response.body());

        assertThat(response.statusCode())
                .as("非 200 通常代表金鑰無效、額度用盡，或 model 名稱不存在，詳見上面印出的 body")
                .isEqualTo(200);

        JsonNode root = MAPPER.readTree(response.body());
        String text = root.at("/candidates/0/content/parts/0/text").asText();
        assertThat(text).isNotBlank();
        System.out.println("Gemini 回覆: " + text);
    }
}
