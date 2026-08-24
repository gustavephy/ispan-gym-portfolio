package tw.com.ispan.gym.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只給 OAuth 這批新程式用——這個專案原本沒有 {@link Clock} 注入的慣例（其他地方一律
 * 直接呼叫 {@code LocalDateTime.now()}），OAuth 因為短效憑證的 TTL 邊界特別多（state
 * 10 分鐘、registration ticket 15 分鐘、exchange code 90 秒），過期邊界測試需要能固定
 * 時間點才不必依賴真實 sleep 或忍受 flaky。刻意不回頭把既有程式碼一起改成用 Clock，
 * 那會擴大這次 OAuth 收斂的範圍到不相關的既有功能。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
