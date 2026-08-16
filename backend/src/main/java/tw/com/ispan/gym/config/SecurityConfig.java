package tw.com.ispan.gym.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tw.com.ispan.gym.security.JsonAccessDeniedHandler;
import tw.com.ispan.gym.security.JsonAuthenticationEntryPoint;
import tw.com.ispan.gym.security.JwtAuthFilter;

/**
 * 正式（非 dev）安全設定：JWT 驗證 + 方法層級授權。
 * dev profile 時整類停用，改由 security.DevSecurityConfig 的全放行 chain
 * 搭配 DevCurrentUserProvider（app.dev-current-user-id）提供固定登入者，
 * 方便本機不帶 token 測試；兩者以 @Profile 互斥，不會同時存在兩條 filter chain。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!dev")
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 純 token 驗證、不使用 cookie session，故關閉 CSRF
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // @Async controller（如 AiPlanController 回 CompletableFuture）完成後的
                        // ASYNC dispatch 不會重跑 JwtAuthFilter，SecurityContext 是空的；
                        // 授權已在原始 REQUEST dispatch 做過，這裡放行以免被二次攔成 401
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登入 / 註冊 / 忘記密碼等免驗證入口
                        .requestMatchers("/api/auth/**").permitAll()
                        // 會員註冊（MemberRegisterController）
                        .requestMatchers(HttpMethod.POST, "/api/members/register").permitAll()
                        // Email 驗證連結由信件點入，此時尚未登入（AccountVerificationController）
                        .requestMatchers(HttpMethod.GET, "/api/accounts/verify-email").permitAll()
                        // 重寄驗證信，同樣是註冊/重新啟用流程的一部分，尚未登入（AccountVerificationController）
                        .requestMatchers(HttpMethod.POST, "/api/accounts/resend-verification-email").permitAll()
                        // 商品/分類瀏覽開放未登入者（前台選單用）。
                        // 注意路徑要與 controller 實際值一致：商品分類是 /api/product-categories
                        //（曾寫成 /api/categories/** 放了空砲，匿名瀏覽分類一直是 401）
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/product-categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/course-categories").permitAll()
                        // 訂閱方案瀏覽開放未登入者（前台展示用），查自己的訂閱狀態仍需登入（見 anyRequest().authenticated()）
                        .requestMatchers(HttpMethod.GET, "/api/subscription-plans").permitAll()
                        // 綠界 server-to-server 付款結果通知，不帶登入態（PaymentController#notify）
                        .requestMatchers(HttpMethod.POST, "/api/payments/ecpay/notify").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 員工基底角色由 JwtAuthFilter 對所有 ACTIVE 員工加發（職位另有 ROLE_ADMIN/ROLE_TRAINER）
                        .requestMatchers("/api/employee/**").hasRole("EMPLOYEE")
                        .anyRequest().authenticated())
                // filter 層的 401／403 不會經過 GlobalExceptionHandler，
                // 這裡改用自訂處理器輸出相同的 ApiErrorResponse 格式，不導向登入頁
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // CORS 設定移至 CorsConfig（dev／非 dev 共用）；PasswordEncoder bean 定義於 PasswordEncoderConfig

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
