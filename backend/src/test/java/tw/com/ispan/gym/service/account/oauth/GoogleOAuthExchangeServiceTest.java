package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.account.OAuthExchangeResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthExchangeCode;
import tw.com.ispan.gym.exception.OAuthExchangeCodeInvalidException;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.JwtUtil;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：對應必補案例「Callback 後 Account 停用，交換拒絕」——
 * 重點在 exchange() 會重新呼叫 {@link AccountLoginPolicy}，不是只信任 callback
 * 當下已經判定過的狀態，堵住 callback 與兌換之間的 TOCTOU 空隙。
 */
class GoogleOAuthExchangeServiceTest {

    private static final String RAW_CODE = "raw-exchange-code";
    private static final String CODE_HASH = "hash-of-exchange-code";
    private static final Integer ACCOUNT_ID = 30;

    private final OAuthExchangeCodeRepository exchangeCodes = mock(OAuthExchangeCodeRepository.class);
    private final AccountLoginPolicy loginPolicy = mock(AccountLoginPolicy.class);
    private final AccountAuthorityResolver authorityResolver = mock(AccountAuthorityResolver.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthExchangeService service = new GoogleOAuthExchangeService(
            exchangeCodes, loginPolicy, authorityResolver, jwtUtil, tokenGenerator, clock);

    @Test
    void exchange_正常交換回傳JWT與ReturnPath() {
        Account account = accountOf();
        OAuthExchangeCode code = codeOf(account, "/orders");
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(code));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(jwtUtil.generateToken(account)).thenReturn("real-jwt");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        OAuthExchangeResponse response = service.exchange(RAW_CODE);

        assertThat(response.accessToken()).isEqualTo("real-jwt");
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.roles()).containsExactly("MEMBER");
        assertThat(response.returnPath()).isEqualTo("/orders");
    }

    @Test
    void exchange_Code不存在丟ExchangeCodeInvalid() {
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findByCodeHash(CODE_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(exchangeCodes, never()).consumeIfUnused(anyString(), any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /** 對應必補案例「一次性交換碼只能使用一次」：原子消耗回傳 0 列，即使唯讀查詢看到還沒用過也要拒絕 */
    @Test
    void exchange_原子消耗回傳0列時丟ExchangeCodeInvalid_即使唯讀查詢看到未使用() {
        Account account = accountOf();
        OAuthExchangeCode code = codeOf(account, null);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(code));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(0);

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /** 對應必補案例「Callback 成功後 Account 在 exchange 前停用」：原子消耗成功後仍要重新判定 Account 狀態 */
    @Test
    void exchange_消耗成功但Account已停用_拒絕不簽發JWT() {
        Account account = accountOf();
        OAuthExchangeCode code = codeOf(account, null);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(code));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(loginPolicy.isLoginAllowed(account)).thenReturn(false);

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /** JWT 的角色一律是兌換當下重新解析，不是 callback 當下算好存起來的快照 */
    @Test
    void exchange_角色使用兌換當下的AuthorityResolver結果() {
        Account account = accountOf();
        OAuthExchangeCode code = codeOf(account, null);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(code));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(jwtUtil.generateToken(account)).thenReturn("real-jwt");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER", "EMPLOYEE"));

        OAuthExchangeResponse response = service.exchange(RAW_CODE);

        assertThat(response.roles()).containsExactly("MEMBER", "EMPLOYEE");
        verify(authorityResolver).resolveRoleNames(account);
    }

    private Account accountOf() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        return account;
    }

    private OAuthExchangeCode codeOf(Account account, String returnPath) {
        OAuthExchangeCode code = new OAuthExchangeCode();
        code.setCodeHash(CODE_HASH);
        code.setAccount(account);
        code.setReturnPath(returnPath);
        code.setExpiresAt(LocalDateTime.now().plusSeconds(90));
        return code;
    }
}
