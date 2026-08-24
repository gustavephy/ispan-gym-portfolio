package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteRequest;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：驗證協調者 complete() 的兩條分派路徑——正常委派給
 * {@link GoogleOAuthRegistrationCreationService}，以及唯一約束衝突時改交給
 * {@link GoogleOAuthRegistrationConflictResolver} 重新判讀（對應必補案例
 * 「唯一約束衝突 → creation rollback 後 conflict resolver 正確分類」的分派半段；
 * conflict resolver 內部分類邏輯本身見 {@link GoogleOAuthRegistrationConflictResolverTest}）。
 */
class GoogleOAuthRegistrationServiceTest {

    private static final String RAW_TOKEN = "raw-registration-token";
    private static final String TOKEN_HASH = "hash-of-registration-token";

    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthRegistrationCreationService creationService =
            mock(GoogleOAuthRegistrationCreationService.class);
    private final GoogleOAuthRegistrationConflictResolver conflictResolver =
            mock(GoogleOAuthRegistrationConflictResolver.class);

    private final GoogleOAuthRegistrationService service =
            new GoogleOAuthRegistrationService(tokenGenerator, creationService, conflictResolver);

    @Test
    void complete_成功時直接回傳CreationService的交換碼_不呼叫ConflictResolver() {
        OAuthRegistrationCompleteRequest request = new OAuthRegistrationCompleteRequest();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(creationService.create(TOKEN_HASH, request)).thenReturn("raw-exchange-code");

        String result = service.complete(RAW_TOKEN, request);

        assertThat(result).isEqualTo("raw-exchange-code");
        verify(conflictResolver, never()).resolve(any());
    }

    /**
     * 對應必補案例的分派半段：creationService.create() 因唯一約束衝突丟出
     * DataIntegrityViolationException 時，必須改用 tokenHash（不是那個已經 detached
     * 的 ticket entity）呼叫 conflictResolver，讓它在全新的 REQUIRES_NEW 交易內重讀。
     */
    @Test
    void complete_唯一約束衝突時改用TokenHash呼叫ConflictResolver() {
        OAuthRegistrationCompleteRequest request = new OAuthRegistrationCompleteRequest();
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(creationService.create(TOKEN_HASH, request))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(conflictResolver.resolve(TOKEN_HASH)).thenThrow(
                new tw.com.ispan.gym.exception.OAuthAccountLinkRequiredException("此 Email 已有帳號"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.complete(RAW_TOKEN, request))
                .isInstanceOf(tw.com.ispan.gym.exception.OAuthAccountLinkRequiredException.class);

        verify(conflictResolver).resolve(eq(TOKEN_HASH));
    }
}
