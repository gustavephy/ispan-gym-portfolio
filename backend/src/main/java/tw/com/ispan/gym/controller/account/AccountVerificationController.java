package tw.com.ispan.gym.controller.account;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.ResendVerificationRequest;
import tw.com.ispan.gym.dto.account.ResendVerificationResponse;
import tw.com.ispan.gym.dto.account.VerifyEmailResponse;
import tw.com.ispan.gym.enums.account.VerificationPurpose;
import tw.com.ispan.gym.service.member.MemberRegisterService;

@RestController
@RequestMapping("/api/accounts")
public class AccountVerificationController {

    private final MemberRegisterService registerService;

    public AccountVerificationController(MemberRegisterService registerService) {
        this.registerService = registerService;
    }

    @GetMapping("/verify-email")
    public VerifyEmailResponse verifyEmail(@RequestParam UUID token) {
        VerificationPurpose purpose = registerService.verifyEmail(token);
        return new VerifyEmailResponse(purpose.name());
    }

    /** 一律回 202 + 同一句話，不論該 email 是否存在或是否真的寄出——避免被用來枚舉已註冊帳號 */
    @PostMapping("/resend-verification-email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResendVerificationResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        registerService.resendVerification(request.email());
        return new ResendVerificationResponse("如果這個 Email 尚待驗證，新的驗證信將會寄出。");
    }
}
