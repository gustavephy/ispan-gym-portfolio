package tw.com.ispan.gym.controller.account;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteRequest;
import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteResponse;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthRegistrationService;

/**
 * 首次 Google 登入的補資料完成端點。匿名可打（落在 {@code /api/auth/**} permitAll），
 * 但 email／Google subject 一律從 server-side 的 registration ticket 取得，
 * request body 不接受、也不信任前端回傳的這兩者——見 {@link GoogleOAuthRegistrationService}。
 */
@RestController
@RequestMapping("/api/auth/oauth/google")
public class OAuthRegistrationController {

    private final GoogleOAuthRegistrationService registrationService;

    public OAuthRegistrationController(GoogleOAuthRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/registration/complete")
    public ResponseEntity<OAuthRegistrationCompleteResponse> complete(
            @Valid @RequestBody OAuthRegistrationCompleteRequest request) {
        String rawExchangeCode = registrationService.complete(request.getRegistrationToken(), request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(new OAuthRegistrationCompleteResponse(rawExchangeCode));
    }
}
