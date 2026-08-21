package tw.com.ispan.gym.controller.account;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.ChangePasswordRequest;
import tw.com.ispan.gym.dto.account.ChangePasswordResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.PasswordService;

/**
 * 登入中修改密碼。密碼是 Account 層級的概念，不分會員／員工，所以不用
 * {@code @PreAuthorize} 限定角色——SecurityConfig 的 anyRequest().authenticated()
 * 已經是唯一需要的門檻，任何已登入帳號都能改自己的密碼。
 *
 * <p>只用 {@link CurrentUserProvider#requireCurrentUserId()} 取得 accountId，
 * request body 不接受 accountId 欄位，避免有人代填別人的帳號 id 越權修改密碼。
 */
@RestController
@RequestMapping("/api/account/me")
public class PasswordController {

    private final PasswordService passwordService;
    private final CurrentUserProvider currentUser;

    public PasswordController(PasswordService passwordService, CurrentUserProvider currentUser) {
        this.passwordService = passwordService;
        this.currentUser = currentUser;
    }

    @PostMapping("/change-password")
    public ChangePasswordResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Integer accountId = currentUser.requireCurrentUserId();
        return passwordService.changePassword(accountId, request);
    }
}
