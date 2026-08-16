package tw.com.ispan.gym.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * dev profile 的全放行 filter chain，讓本機不必帶 token 就能打 API。
 * 與 {@code config.SecurityConfig}（{@code @Profile("!dev")}）互斥。
 *
 * <p>因為 application.properties 目前寫死 {@code spring.profiles.active=dev}，
 * 未覆寫就啟動＝整個 API 不設防，且 @PreAuthorize 與 SecurityConfig 的所有規則都不會生效。
 * 這種狀態沒有任何外顯徵兆，開發期間也感受不到權限問題（權限相關的 bug 會一路潛伏到正式環境
 * 才爆出來），因此啟動時印一段醒目的警告，讓「現在沒有任何保護」這件事無法被忽略。
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DevSecurityConfig.class);

    @PostConstruct
    void warnSecurityDisabled() {
        log.warn("""

                ***************************************************************
                *  警告：dev profile 已啟用，API 目前完全不設防                *
                *                                                             *
                *  - 所有端點皆可匿名存取，@PreAuthorize 一律不生效            *
                *  - 登入身分固定為 app.dev-current-user-id，非真實登入者      *
                *                                                             *
                *  僅供本機開發使用。部署到任何對外環境前，務必改用其他        *
                *  profile（例如 SPRING_PROFILES_ACTIVE=prod）啟動。           *
                ***************************************************************
                """);
    }

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 沿用 CorsConfig 的共用設定；少了這行，前端本機開發的 preflight 會被瀏覽器擋下
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}