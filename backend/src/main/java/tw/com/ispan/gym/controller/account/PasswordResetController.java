package tw.com.ispan.gym.controller.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.ForgotPasswordRequest;
import tw.com.ispan.gym.dto.account.ForgotPasswordResponse;
import tw.com.ispan.gym.dto.account.ResetPasswordRequest;
import tw.com.ispan.gym.dto.account.ResetPasswordResponse;
import tw.com.ispan.gym.service.account.PasswordService;

/**
 * 忘記密碼申請／憑 Token 重設密碼，都是匿名端點，落在 SecurityConfig 既有的
 * {@code /api/auth/**} permitAll 規則裡，不需要改設定。跟只處理登入的
 * {@link AuthController} 分開成獨立 controller，比照 AccountVerificationController
 * 從 MemberRegisterController 拆出來的既有慣例（同一個路徑前綴下可以有多個 controller，
 * 只要實際端點路徑不重疊）。
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordService passwordService;

    public PasswordResetController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    /** 一律回 202 + 同一句話，不論 Email 是否存在、是否為 OAuth-only、是否被限流——防列舉 */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        passwordService.requestPasswordReset(request.email(), httpRequest.getRemoteAddr());
        return new ForgotPasswordResponse("若此 Email 可使用密碼登入，我們將寄送密碼重設信。");
    }

    /**
     * 成功回 200 + {@link ResetPasswordResponse}（token 所屬帳號的 Email），
     * 供前端跨分頁比對「這封重設信是不是同一個帳號的」。持有有效 token 已等同證明
     * 能收該信箱的信，回傳 Email 不構成帳號列舉。失敗一律 400 RESET_TOKEN_INVALID。
     */
    @PostMapping("/reset-password")
    public ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String email = passwordService.resetPassword(request.getToken(), request.getNewPassword());
        return new ResetPasswordResponse(email);
    }
}
