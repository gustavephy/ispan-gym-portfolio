package tw.com.ispan.gym.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

/**
 * AI 呼叫用的獨立執行緒池，不依賴預設的 SimpleAsyncTaskExecutor（那個沒有上限，
 * 高併發時會無限開執行緒）。核心/上限池大小刻意壓低，因為呼叫對象是外部 AI API，
 * 瓶頸在對方回應速度，開再多執行緒本地端也沒用，只會浪費資源排隊等待。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-async-");
        executor.initialize();
        return executor;
    }

    /** 逾時設寬鬆一點，AI 生成課表比一般 API 慢很多是常態，不是異常。 */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }
}
