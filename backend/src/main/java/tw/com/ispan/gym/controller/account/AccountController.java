package tw.com.ispan.gym.controller.account;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.dto.account.AccountMeResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.AccountService;

/**
 * 帳號層級的自助查詢與操作：身份快照、全裝置登出。密碼管理見 {@link PasswordController}，
 * Google 綁定見 {@link OAuthConnectionController}——三者共用同一個 {@code /api/account/me}
 * 路徑前綴但分屬不同 Controller，比照既有分工方式。
 *
 * <p>accountId 一律來自 {@link CurrentUserProvider}，不接受任何形式的外部輸入；不需要
 * {@code SecurityConfig} 額外白名單，落在既有 {@code anyRequest().authenticated()} 底下。
 */
@RestController
@RequestMapping("/api/account/me")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUserProvider currentUser;

    public AccountController(AccountService accountService, CurrentUserProvider currentUser) {
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public AccountMeResponse me() {
        return accountService.getMe(currentUser.requireCurrentUserId());
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAllDevices() {
        accountService.logoutAllDevices(currentUser.requireCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
