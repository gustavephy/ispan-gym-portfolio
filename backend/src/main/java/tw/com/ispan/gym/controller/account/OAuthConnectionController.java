package tw.com.ispan.gym.controller.account;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.account.OAuthConnectionsResponse;
import tw.com.ispan.gym.dto.account.OAuthLinkInitiateResponse;
import tw.com.ispan.gym.dto.account.OAuthReauthRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthConnectionsService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthLinkInitiationService;
import tw.com.ispan.gym.service.account.oauth.GoogleOAuthUnlinkService;

/**
 * Google 綁定的自助管理：查詢狀態、發起 LINK、解除 LINK。都只作用在目前登入的
 * Account——accountId 一律來自 {@link CurrentUserProvider}，request body 不接受
 * accountId 欄位，比照 {@link PasswordController} 同一套慣例。
 *
 * <p>不需要 SecurityConfig 額外白名單——落在既有 anyRequest().authenticated() 底下。
 */
@RestController
@RequestMapping("/api/account/me")
public class OAuthConnectionController {

    private final GoogleOAuthConnectionsService connectionsService;
    private final GoogleOAuthLinkInitiationService linkInitiationService;
    private final GoogleOAuthUnlinkService unlinkService;
    private final CurrentUserProvider currentUser;

    public OAuthConnectionController(GoogleOAuthConnectionsService connectionsService,
            GoogleOAuthLinkInitiationService linkInitiationService, GoogleOAuthUnlinkService unlinkService,
            CurrentUserProvider currentUser) {
        this.connectionsService = connectionsService;
        this.linkInitiationService = linkInitiationService;
        this.unlinkService = unlinkService;
        this.currentUser = currentUser;
    }

    @GetMapping("/oauth-connections")
    public OAuthConnectionsResponse connections() {
        return connectionsService.query(currentUser.requireCurrentUserId());
    }

    @PostMapping("/oauth/google/link")
    public OAuthLinkInitiateResponse link(@Valid @RequestBody OAuthReauthRequest request) {
        String rawState = linkInitiationService.initiate(currentUser.requireCurrentUserId(),
                request.getCurrentPassword());
        return new OAuthLinkInitiateResponse("/oauth2/authorization/google?transaction=" + rawState);
    }

    @DeleteMapping("/oauth/google")
    public ResponseEntity<Void> unlink(@Valid @RequestBody OAuthReauthRequest request) {
        unlinkService.unlink(currentUser.requireCurrentUserId(), request.getCurrentPassword());
        return ResponseEntity.noContent().build();
    }
}
