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
                        // 員工註冊，PENDING_APPROVAL 後改走員工審核，不是這裡驗證（EmployeeRegisterController）
                        .requestMatchers(HttpMethod.POST, "/api/employees/register").permitAll()
                        // REJECTED 重新申請：申請者沒有 JWT，密碼驗證由端點自己做，不靠登入態
                        // （EmployeeRegisterController）
                        .requestMatchers(HttpMethod.POST, "/api/employees/reapply").permitAll()
                        // Email 驗證連結由信件點入，此時尚未登入（AccountVerificationController）
                        .requestMatchers(HttpMethod.GET, "/api/accounts/verify-email").permitAll()
                        // 重寄驗證信，同樣是註冊/重新啟用流程的一部分，尚未登入（AccountVerificationController）
                        .requestMatchers(HttpMethod.POST, "/api/accounts/resend-verification-email").permitAll()
                        // 商品/分類瀏覽開放未登入者（前台選單用）。
                        // 注意路徑要與 controller 實際值一致：商品分類是 /api/product-categories
                        //（曾寫成 /api/categories/** 放了空砲，匿名瀏覽分類一直是 401）
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/product-categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/course-categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/group-courses").permitAll()
                        // 公開教練介紹頁／列表（TrainerController）；未軟刪除者皆可匿名瀏覽，
                        // UNAVAILABLE 教練同樣公開，由回應的 status 讓前端自行停用預約入口
                        .requestMatchers(HttpMethod.GET, "/api/trainers", "/api/trainers/*").permitAll()
                        // 課表瀏覽開放未登入者：教練可預約時段（CoachBookingController#searchSlots）與
                        // 團課場次列表（GroupClassController#getUpcomingSessions）都只是公開排班資訊，
                        // 實際「預約／報名」的動作才需要登入。
                        // 路徑必須逐條精確列出、不能寫成 /api/member/**——同一前綴下還掛著
                        // GET /api/member/bookings（會員自己的預約紀錄）等個人資料端點，
                        // 一併放行會把它們也曝光成匿名可讀。
                        .requestMatchers(HttpMethod.GET, "/api/member/trainers/*/schedules").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/member/group-classes").permitAll()
                        // 訂閱方案瀏覽開放未登入者（前台展示用），查自己的訂閱狀態仍需登入（見 anyRequest().authenticated()）
                        .requestMatchers(HttpMethod.GET, "/api/subscription-plans").permitAll()
                        // 綠界 server-to-server 付款結果通知，不帶登入態（PaymentController#notify）
                        .requestMatchers(HttpMethod.POST, "/api/payments/ecpay/notify").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // SockJS 交握本身是一般 HTTP 請求（GET /ws-chat/info、POST .../xhr 等），
                        // 不會帶 JWT bearer header；真正的登入驗證在 STOMP CONNECT frame 階段
                        // 由 StompAuthChannelInterceptor 處理，這裡放行純粹是為了讓交握能建立
                        .requestMatchers("/ws-chat/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 員工基底角色由 AccountAuthorityResolver 對所有 ACTIVE 員工加發（職位另有 ROLE_ADMIN/ROLE_TRAINER）
                        .requestMatchers("/api/employee/**").hasRole("EMPLOYEE")
                        // 員工審核：任何在職員工皆可審核，不限管理者，獨立於 /api/admin/** 之外
                        // （/api/admin/** 還掛著訂單、商品等真正的管理員專屬後台，不能共用同一條規則）
                        .requestMatchers("/api/employees/reviews/**").hasRole("EMPLOYEE")
                        // 員工升級為教練：限管理者，與上面的員工審核（任何在職員工皆可）是不同動作的不同權限
                        .requestMatchers(HttpMethod.POST, "/api/employees/*/trainer").hasRole("ADMIN")
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
