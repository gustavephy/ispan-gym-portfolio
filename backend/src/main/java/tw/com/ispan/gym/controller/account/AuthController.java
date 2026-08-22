package tw.com.ispan.gym.controller.account;

import tw.com.ispan.gym.dto.account.LoginRequest;
import tw.com.ispan.gym.dto.account.LoginResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.JwtUtil;

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
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountAuthorityResolver authorityResolver;
    private final AccountLoginPolicy loginPolicy;

    public AuthController(AccountRepository accounts, EmployeeRepository employees,
            PasswordEncoder passwordEncoder, JwtUtil jwtUtil, AccountAuthorityResolver authorityResolver,
            AccountLoginPolicy loginPolicy) {
        this.accounts = accounts;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authorityResolver = authorityResolver;
        this.loginPolicy = loginPolicy;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Account account = accounts.findByEmailIgnoreCase(request.email())
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BadCredentialsException("帳號或密碼錯誤");
        }

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
                    case REJECTED -> new DisabledException("員工申請未通過，如需重新申請請使用重新申請功能。");
                    default -> new DisabledException("此帳號目前沒有可用的身分，請聯繫管理員。");
                })
                .orElseGet(() -> new DisabledException("此帳號目前沒有可用的身分，請聯繫管理員。"));
    }
}
