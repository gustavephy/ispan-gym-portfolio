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

    /**
     * 忘記密碼信件專用，不跟 aiTaskExecutor 共用——瓶頸與流量特性不同（SMTP 而非外部 AI API），
     * 容量刻意壓得比 AI 池更小，這個專案的寄信量遠低於 AI 呼叫。同步寄信會讓「帳號存在」
     * 的回應明顯比「帳號不存在」慢，等於用回應時間洩漏帳號是否存在，所以密碼重設信
     * 一律非同步寄送，交易 commit 後才丟進這個池子（見 PasswordService）。
     */
    @Bean(name = "mailTaskExecutor")
    public Executor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        executor.initialize();
        return executor;
    }

    /** 逾時設寬鬆一點，AI 生成課表比一般 API 慢很多是常態，不是異常。 */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        return new RestTemplate(factory);
    }
}
