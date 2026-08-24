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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * dev profile 的全放行 filter chain，讓本機不必帶 token 就能打 API。
 * 與 {@code config.SecurityConfig}（{@code @Profile("!dev")}）互斥。
 *
 * <p>因為 application.properties 目前寫死 {@code spring.profiles.active=dev}，
 * 未覆寫就啟動＝整個 API 不設防，且 @PreAuthorize 與 SecurityConfig 的所有規則都不會生效。
 * 這種狀態沒有任何外顯徵兆，開發期間也感受不到權限問題（權限相關的 bug 會一路潛伏到正式環境
 * 才爆出來），因此啟動時印一段醒目的警告，讓「現在沒有任何保護」這件事無法被忽略。
 *
 * <p>警告中刻意不指名某個現成的正式 profile：專案目前只有這一份 application.properties，
 * 沒有 application-prod 之類的設定檔。切到 dev 以外的 profile 只會讓
 * {@code @Profile("!dev")} 的那組 bean 生效（JWT 驗證、真實身分解析），
 * 其餘設定仍讀同一份檔案的本機預設值——包含已進版控、等同公開的 JWT 金鑰與種子管理員密碼。
 * 只換 profile 名稱會得到「看似有防護、金鑰卻是公開的」狀態，比 dev 全放行更難察覺。
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DevSecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;

    DevSecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @PostConstruct
    void warnSecurityDisabled() {
        log.warn("""

                ***************************************************************
                *  警告：dev profile 已啟用，API 目前完全不設防                *
                *                                                             *
                *  - 所有端點皆可匿名存取，@PreAuthorize 一律不生效            *
                *  - 登入身分固定為 app.dev-current-user-id，非真實登入者      *
                *                                                             *
                *  僅供本機開發使用。要改跑正式安全設定，兩種方式擇一：        *
                *  1. 設環境變數 SPRING_PROFILES_ACTIVE=<非 dev 的名稱>        *
                *     （優先序高於設定檔，不必改檔案，部署時用這個）           *
                *  2. 註解掉 application.properties 的 spring.profiles.active *
                *     （本機臨時想驗權限時最直接，但別提交上去）               *
                *                                                             *
                *  注意：專案目前只有這一份 application.properties，沒有       *
                *  對外用的設定檔。切換 profile 只會啟用 JWT 驗證，其餘仍讀    *
                *  同一份檔案的本機預設值（JWT 金鑰、種子管理員密碼都已進      *
                *  版控，等同公開）。對外部署前必須另外提供 JWT_SECRET／       *
                *  DB_PASSWORD／SEED_ADMIN_* 等環境變數，並關閉 ddl-auto。     *
                ***************************************************************
                """);
    }

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 沿用 CorsConfig 的共用設定；少了這行，前端本機開發的 preflight 會被瀏覽器擋下
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 全放行仍要掛 JwtAuthFilter：有帶 token 時把真實登入者寫進 SecurityContext，
                // 讓 DevCurrentUserProvider 能解析出真實身分。少了這行，REST 一律回寫死的
                // 假 id，但 STOMP 驗證走的是真實 JWT，兩邊對「我是誰」的認知會兜不起來，
                // 聊天室訂閱因而被 authorizeRoomSubscription 擋下（見 DevCurrentUserProvider）。
                // 不帶 token 時 filter 什麼都不做，「本機不登入也能打 API」的便利性不受影響。
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}