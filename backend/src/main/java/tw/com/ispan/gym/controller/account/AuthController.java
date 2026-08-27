package tw.com.ispan.gym.controller.account;

import java.time.Duration;
import java.util.Locale;

import tw.com.ispan.gym.dto.account.LoginRequest;
import tw.com.ispan.gym.dto.account.LoginResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.exception.LoginRateLimitedException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.security.JwtUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 最小的 email/密碼登入端點。SecurityConfig 已對 /api/auth/** permitAll。
 *
 * <p>密碼驗證通過後，還會檢查帳號是否至少有一個目前可用的身分（見
 * {@link AccountLoginPolicy}，Google OAuth 登入／JWT 兌換共用同一套判定）——
 * 都沒有的話不簽發 token，改依 Employee 狀態回覆具體訊息（例如「尚待審核」），
 * 而不是讓前端拿到一個實際上什麼都做不了的 JWT。這不算新的匿名洩漏：密碼已經
 * 驗證過，等於已經證明是本人，跟這個專案 verifyEmail() 已經用過的「持有憑證即
 * 視為本人」原則一致。若同一帳號還有其他可用身分（例如 Member ACTIVE），
 * 仍正常發 token——JwtAuthFilter 本來就會依各身分狀態個別授權，這裡沒有新增行為，
 * 只是決定要不要一開始就發 token。
 *
 * <p>登入限流沿用 {@link InMemoryRateLimiter}（與密碼管理的忘記密碼／修改密碼共用同一元件，
 * 見 {@code PasswordService}）。IP 門檻直接沿用忘記密碼既有的數字（每分鐘 10 次、每小時 30 次），
 * 不另外調整——若日後真的要為校園／公司等共用 IP 場景放寬，應是一次獨立的規格決策，
 * 而非在這裡的實作順手改掉。同 IP 的限流會先檢查——沒超額才會去動 email 專屬的桶，
 * 避免有人用大量不存在的 email 從同一支 IP 狂送，把 email 那邊的 map 灌爆
 * （比照 {@code PasswordService.withinForgotPasswordRateLimits} 既有的先後順序）。
 * email 桶在密碼驗證「成功」的當下就清空，不等到實際核發 token——密碼答對已證明是本人，
 * 不該再算他是在猜密碼，即使帳號隨後因為沒有可用身分而發不出 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** IP 層級是次要、較粗略的防線（真正防猜密碼靠下面的 email 門檻），門檻與忘記密碼一致 */
    private static final int LOGIN_IP_MINUTE_MAX = 10;
    private static final int LOGIN_IP_HOURLY_MAX = 30;
    /** email 層級是主要防線，門檻與修改密碼的猜測防護一致（同一組 15 分鐘／5 次） */
    private static final int LOGIN_EMAIL_MAX_ATTEMPTS = 5;
    private static final Duration LOGIN_EMAIL_WINDOW = Duration.ofMinutes(15);

    private final AccountRepository accounts;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountAuthorityResolver authorityResolver;
    private final AccountLoginPolicy loginPolicy;
    private final InMemoryRateLimiter rateLimiter;

    public AuthController(AccountRepository accounts, EmployeeRepository employees,
            PasswordEncoder passwordEncoder, JwtUtil jwtUtil, AccountAuthorityResolver authorityResolver,
            AccountLoginPolicy loginPolicy, InMemoryRateLimiter rateLimiter) {
        this.accounts = accounts;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authorityResolver = authorityResolver;
        this.loginPolicy = loginPolicy;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String clientIp = httpRequest.getRemoteAddr();

        if (!rateLimiter.tryAcquire("login:ip-minute:" + clientIp, LOGIN_IP_MINUTE_MAX, Duration.ofMinutes(1))
                || !rateLimiter.tryAcquire("login:ip-hourly:" + clientIp, LOGIN_IP_HOURLY_MAX, Duration.ofHours(1))) {
            throw new LoginRateLimitedException("登入嘗試次數過多，請稍後再試");
        }
        String emailRateLimitKey = "login:email:" + normalizedEmail;
        if (!rateLimiter.tryAcquire(emailRateLimitKey, LOGIN_EMAIL_MAX_ATTEMPTS, LOGIN_EMAIL_WINDOW)) {
            throw new LoginRateLimitedException("登入嘗試次數過多，請稍後再試");
        }

        Account account = accounts.findByEmailIgnoreCase(request.email())
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BadCredentialsException("帳號或密碼錯誤");
        }
        rateLimiter.clear(emailRateLimitKey);

        if (!loginPolicy.isLoginAllowed(account)) {
            throw employeeStatusMessage(account);
        }

        // roles＝展開後的有效權限（與 JwtAuthFilter／AccountAuthorityResolver 同一套規則），供前端導流
        return new LoginResponse(jwtUtil.generateToken(account), account.getId(), account.getEmail(),
                authorityResolver.resolveRoleNames(account));
    }

    private DisabledException employeeStatusMessage(Account account) {
        return employees.findByAccountId(account.getId())
                .filter(e -> e.getDeletedAt() == null)
                .map(e -> switch (e.getStatus()) {
                    case PENDING_APPROVAL -> new DisabledException("員工帳號尚待審核，請等候通知。");
                    case REJECTED -> new DisabledException(rejectedMessage(e));
                    default -> new DisabledException("此帳號目前沒有可用的身分，請聯繫管理員。");
                })
                .orElseGet(() -> new DisabledException("此帳號目前沒有可用的身分，請聯繫管理員。"));
    }

    /**
     * REJECTED 的登入錯誤訊息帶上審核者填寫的拒絕原因，讓申請人不必另外詢問就能知道
     * 為什麼被拒、下一步該怎麼做。這裡不算新的匿名洩漏——密碼已經在 login() 驗證過
     * 才會走到這個分支，等於已證明是本人（同一套原則見本類別上方 Javadoc）。
     */
    private String rejectedMessage(Employee employee) {
        String reason = employee.getReviewNote();
        if (reason == null || reason.isBlank()) {
            return "員工申請未通過，如需重新申請請使用重新申請功能。";
        }
        return "員工申請未通過，原因：" + reason.strip() + "。如需重新申請請使用重新申請功能。";
    }
}
