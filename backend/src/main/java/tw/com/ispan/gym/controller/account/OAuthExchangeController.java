package tw.com.ispan.gym.controller.account;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.OAuthExchangeRequest;
import tw.com.ispan.gym.dto.account.OAuthExchangeResponse;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthExchangeService;

/**
 * 匿名可打（落在 SecurityConfig 的 {@code /api/auth/**} permitAll），但不靠端點層級
 * 的登入驗證把關——持有短效、單次使用、高熵的交換碼才做得了事，語意等同已經證明
 * 完成一次成功的 Google 登入。回應不進 Cache，避免中繼 Proxy／瀏覽器留存含 JWT 的內容。
 */
@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthExchangeController {

    private final GoogleOAuthExchangeService exchangeService;

    public OAuthExchangeController(GoogleOAuthExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @PostMapping("/exchange")
    public ResponseEntity<OAuthExchangeResponse> exchange(@Valid @RequestBody OAuthExchangeRequest request) {
        OAuthExchangeResponse response = exchangeService.exchange(request.code());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(response);
    }
}
