package tw.com.ispan.gym.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tw.com.ispan.gym.security.JsonAccessDeniedHandler;
import tw.com.ispan.gym.security.JsonAuthenticationEntryPoint;
import tw.com.ispan.gym.security.JwtAuthFilter;
import tw.com.ispan.gym.security.oauth.GoogleOAuthAuthorizationRequestResolver;
import tw.com.ispan.gym.security.oauth.GoogleOAuthFailureHandler;
import tw.com.ispan.gym.security.oauth.GoogleOAuthSuccessHandler;

/**
 * 正式（非 dev）安全設定：JWT 驗證 + 方法層級授權，另外拆出一條 OAuth 協定專用鏈。
 * dev profile 時整類停用，改由 security.DevSecurityConfig 的全放行 chain
 * 搭配 DevCurrentUserProvider（app.dev-current-user-id）提供固定登入者，
 * 方便本機不帶 token 測試；兩者以 @Profile 互斥，不會同時存在兩條 filter chain。
 *
 * <p>兩條鏈刻意分開（2026-08-22 定案，見 docs/google-oauth-design-notes.md）：
 * <ul>
 * <li>{@code oauthSecurityFilterChain}（{@code @Order(1)}）只匹配 {@code /oauth2/**}／
 * {@code /login/oauth2/**}，用 {@code IF_REQUIRED} session——這個 session 只保存
 * Spring Security 自己的 {@code AuthorizationRequestRepository}（PKCE verifier、
 * redirect URI 等協定資料），<b>不代表系統登入狀態</b>。
 * <li>{@code apiSecurityFilterChain}（{@code @Order(2)}）維持 {@code STATELESS}，
 * 永遠只信任 JWT——即使瀏覽器帶著 OAuth 握手期間留下的 {@code JSESSIONID} 呼叫 API，
 * 這條鏈也不會把 session 當登入依據。
 * </ul>
 * v1 用 Spring 預設的 {@code HttpSession} 版 {@code AuthorizationRequestRepository}，
 * 因此僅限單 instance 部署；多 instance 需要 sticky session 或改用共用/持久化的
 * repository，屬已知限制，尚未實作。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!dev")
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;
    private final GoogleOAuthAuthorizationRequestResolver oauthAuthorizationRequestResolver;
    private final GoogleOAuthSuccessHandler oauthSuccessHandler;
    private final GoogleOAuthFailureHandler oauthFailureHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            GoogleOAuthAuthorizationRequestResolver oauthAuthorizationRequestResolver,
            GoogleOAuthSuccessHandler oauthSuccessHandler,
            GoogleOAuthFailureHandler oauthFailureHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.oauthAuthorizationRequestResolver = oauthAuthorizationRequestResolver;
        this.oauthSuccessHandler = oauthSuccessHandler;
        this.oauthFailureHandler = oauthFailureHandler;
    }

    @Bean
    @Order(1)
    SecurityFilterChain oauthSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // 只保存協定握手資料，不是系統登入態；API 鏈仍然只信任 JWT（見類別註解）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(oauthAuthorizationRequestResolver))
                        .successHandler(oauthSuccessHandler)
                        .failureHandler(oauthFailureHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
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
                        // 商品／課程圖片，前台未登入也要看得到（上傳仍受 /api/admin/** 保護）。
                        // 不限定 GET：瀏覽器與快取／預載機制也會對圖片發 HEAD，只放 GET 會回 401
                        .requestMatchers("/uploads/**").permitAll()
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
                        // 註冊表單即時可用性檢查，填表單當下尚未登入（AccountVerificationController）
                        .requestMatchers(HttpMethod.POST, "/api/accounts/check-email-availability").permitAll()
                        // 商品/分類瀏覽開放未登入者（前台選單用）。
                        // 注意路徑要與 controller 實際值一致：商品分類是 /api/product-categories
                        //（曾寫成 /api/categories/** 放了空砲，匿名瀏覽分類一直是 401）
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/product-categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/course-categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/group-courses").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/private-courses", "/api/private-courses/paged").permitAll()
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
                        // ATM／CVS 取號結果通知，跟上面 notify 是不同的 callback URL（PaymentController#paymentInfo）
                        .requestMatchers(HttpMethod.POST, "/api/payments/ecpay/payment-info").permitAll()
                        // 注意：POST /api/auth/oauth/exchange 與
                        // /api/auth/oauth/google/registration/complete 不需要在這裡另外列——
                        // 已經落在上面 "/api/auth/**" permitAll 規則裡；安全性不是靠端點層級
                        // 的登入驗證，而是靠持有一次性交換碼／registration token，見兩個
                        // Controller 各自的類別註解
                        // SockJS 交握本身是一般 HTTP 請求（GET /ws-chat/info、POST .../xhr 等），
                        // 不會帶 JWT bearer header；真正的登入驗證在 STOMP CONNECT frame 階段
                        // 由 StompAuthChannelInterceptor 處理，這裡放行純粹是為了讓交握能建立
                        .requestMatchers("/ws-chat/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 員工基底角色由 AccountAuthorityResolver 對所有 ACTIVE 員工加發（職位另有 ROLE_ADMIN/ROLE_TRAINER）
                        .requestMatchers("/api/employee/**").hasRole("EMPLOYEE")
                        // 教練本人專業資料：ROLE_TRAINER 由 AccountAuthorityResolver 算出
                        // （員工 ACTIVE 且有對應未軟刪除的 Trainer 資料列才授予），不與 EMPLOYEE/ADMIN 互斥
                        .requestMatchers("/api/trainer/**").hasRole("TRAINER")
                        // 員工審核：ADMIN-only（2026-08-31 政策定案）。路徑仍獨立於 /api/admin/** 之外
                        // ——那條 prefix 掛著訂單、商品等其他後台功能，保留獨立 matcher 方便日後若要
                        // 放寬回「任何在職員工皆可審核」只需改這一行。角色為疊加式，ADMIN 帳號必同時
                        // 具 ROLE_EMPLOYEE，故 hasRole("ADMIN") 已足夠。
                        .requestMatchers("/api/employees/reviews/**").hasRole("ADMIN")
                        // 員工升級為教練：同為限管理者
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

    // 刻意不暴露 AuthenticationManager bean（曾經有、已移除）：整個專案的密碼登入都是
    // PasswordService 手動比對 BCrypt + 自簽 JWT，沒有任何地方呼叫 AuthenticationManager
    // .authenticate()；用 configuration.getAuthenticationManager() 建立這個 bean，在
    // 「多條 SecurityFilterChain + oauth2Login()」這個組合下會觸發 Spring Security 已知的
    // 初始化順序問題，導致 authenticate() 遞迴呼叫自己而 StackOverflowError（2026-08-22
    // 用真實 Google 帳號在同意畫面按「取消」時實測炸出來，是這個 bean 本身沒被用到卻仍在
    // 觸發自我遞迴，不是 access_denied 的處理邏輯有問題）。
}
