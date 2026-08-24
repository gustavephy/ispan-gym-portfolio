package tw.com.ispan.gym.controller.account;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.CheckEmailAvailabilityRequest;
import tw.com.ispan.gym.dto.account.CheckEmailAvailabilityResponse;
import tw.com.ispan.gym.dto.account.ResendVerificationRequest;
import tw.com.ispan.gym.dto.account.ResendVerificationResponse;
import tw.com.ispan.gym.dto.account.VerifyEmailResponse;
import tw.com.ispan.gym.enums.account.VerificationPurpose;
import tw.com.ispan.gym.exception.EmailCheckRateLimitedException;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.service.member.MemberRegisterService;

@RestController
@RequestMapping("/api/accounts")
public class AccountVerificationController {

    /** 門檻與登入的 IP 層一致（見 AuthController），這支同樣是匿名端點、同樣要防止被拿來大量掃描 */
    private static final int CHECK_EMAIL_IP_MINUTE_MAX = 10;
    private static final int CHECK_EMAIL_IP_HOURLY_MAX = 30;

    private final MemberRegisterService registerService;
    private final InMemoryRateLimiter rateLimiter;

    public AccountVerificationController(MemberRegisterService registerService, InMemoryRateLimiter rateLimiter) {
        this.registerService = registerService;
        this.rateLimiter = rateLimiter;
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

    /**
     * 註冊表單即時可用性檢查——刻意接受這是一個帳號列舉管道，用限流降低被拿來
     * 大量掃描的風險，而不是假裝這個端點不存在這個問題。真正的唯一性判斷仍在
     * {@code POST /api/members/register}，這支只是給打字當下的即時提示用，
     * 不會複製註冊那邊的重寄冷卻檢查（見 {@link MemberRegisterService#isEmailAvailableForRegistration}）。
     */
    @PostMapping("/check-email-availability")
    public CheckEmailAvailabilityResponse checkEmailAvailability(
            @Valid @RequestBody CheckEmailAvailabilityRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        boolean withinLimit =
                rateLimiter.tryAcquire("check-email:ip-minute:" + clientIp, CHECK_EMAIL_IP_MINUTE_MAX, Duration.ofMinutes(1))
                        && rateLimiter.tryAcquire("check-email:ip-hourly:" + clientIp, CHECK_EMAIL_IP_HOURLY_MAX, Duration.ofHours(1));
        if (!withinLimit) {
            throw new EmailCheckRateLimitedException("檢查次數過多，請稍後再試");
        }
        return new CheckEmailAvailabilityResponse(registerService.isEmailAvailableForRegistration(request.email()));
    }
}
