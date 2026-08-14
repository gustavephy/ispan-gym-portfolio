package tw.com.ispan.gym.controller.account;

import tw.com.ispan.gym.dto.account.LoginRequest;
import tw.com.ispan.gym.dto.account.LoginResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.JwtUtil;

import jakarta.validation.Valid;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 最小的 email/密碼登入端點。SecurityConfig 已對 /api/auth/** permitAll。
 * 只做帳號存在＋密碼比對＋簽發 JWT；帳號/會員/員工狀態是否可用交由各自的
 * CurrentUserProvider／JwtAuthFilter 在後續請求時檢查（同一設計已用於 OAuth2 登入路徑）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountAuthorityResolver authorityResolver;

    public AuthController(AccountRepository accounts, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
            AccountAuthorityResolver authorityResolver) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authorityResolver = authorityResolver;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Account account = accounts.findByEmailIgnoreCase(request.email())
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BadCredentialsException("帳號或密碼錯誤");
        }
        // roles＝展開後的有效權限（與 JwtAuthFilter 同一套規則），供前端導流
        return new LoginResponse(jwtUtil.generateToken(account), account.getId(), account.getEmail(),
                authorityResolver.resolveRoleNames(account));
    }
}
