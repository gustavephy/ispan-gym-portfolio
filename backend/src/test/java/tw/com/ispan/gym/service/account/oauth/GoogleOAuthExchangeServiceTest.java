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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.account.OAuthExchangeResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.exception.OAuthExchangeCodeInvalidException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeLookup;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.AccountLoginPolicy;
import tw.com.ispan.gym.security.JwtUtil;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：對應必補案例「Callback 後 Account 停用，交換拒絕」與
 * 2026-08-22 code review 補上的「核發後 credentialsVersion 改變，交換拒絕」——
 * exchange() 會重新鎖 Account、重新呼叫 {@link AccountLoginPolicy}、比對核發當下
 * 的版本快照，不是只信任 callback 當下已經判定過的狀態，堵住 callback 與兌換之間
 * 的兩種 TOCTOU 空隙（帳號被停用／帳號的登入憑證被撤銷）。
 */
class GoogleOAuthExchangeServiceTest {

    private static final String RAW_CODE = "raw-exchange-code";
    private static final String CODE_HASH = "hash-of-exchange-code";
    private static final Integer ACCOUNT_ID = 30;

    private final OAuthExchangeCodeRepository exchangeCodes = mock(OAuthExchangeCodeRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountLoginPolicy loginPolicy = mock(AccountLoginPolicy.class);
    private final AccountAuthorityResolver authorityResolver = mock(AccountAuthorityResolver.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final Clock clock = Clock.systemDefaultZone();

    private final GoogleOAuthExchangeService service = new GoogleOAuthExchangeService(
            exchangeCodes, accounts, loginPolicy, authorityResolver, jwtUtil, tokenGenerator, clock);

    @Test
    void exchange_正常交換回傳JWT與ReturnPath() {
        Account account = accountOf(0);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(0, "/orders")));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
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
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(exchangeCodes, never()).consumeIfUnused(anyString(), any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /** 對應必補案例「一次性交換碼只能使用一次」：原子消耗回傳 0 列，即使唯讀查詢看到還沒用過也要拒絕 */
    @Test
    void exchange_原子消耗回傳0列時丟ExchangeCodeInvalid_即使唯讀查詢看到未使用() {
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(0, null)));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(0);

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(accounts, never()).findByIdForUpdate(any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /** 對應必補案例「Callback 成功後 Account 在 exchange 前停用」：消耗成功後仍要重新判定 Account 狀態 */
    @Test
    void exchange_消耗成功但Account已停用_拒絕不簽發JWT() {
        Account account = accountOf(0);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(0, null)));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(false);

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    /**
     * 對應 2026-08-22 code review 補上的必修案例：核發之後、兌換之前發生 UNLINK／改密碼／
     * 全裝置登出，credentialsVersion 遞增——即使 Code 本身還沒過期、消耗也成功，仍必須拒絕，
     * 不能讓一次已經被使用者自己撤銷過的 Google 登入事件，事後還能補簽出一張新版本的 JWT。
     */
    @Test
    void exchange_核發後CredentialsVersion已變_丟ExchangeCodeInvalid_不簽發JWT() {
        Account account = accountOf(1); // 核發後 UNLINK 已經把版本從 0 遞增到 1
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(0, null)));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.exchange(RAW_CODE))
                .isInstanceOf(OAuthExchangeCodeInvalidException.class);

        // 版本不符在檢查 AccountLoginPolicy 之前就該拒絕，帳號本身是否仍可登入已經不重要
        verify(loginPolicy, never()).isLoginAllowed(any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    @Test
    void exchange_核發後CredentialsVersion未變_正常簽發JWT() {
        Account account = accountOf(2);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(2, null)));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(jwtUtil.generateToken(account)).thenReturn("real-jwt");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        OAuthExchangeResponse response = service.exchange(RAW_CODE);

        assertThat(response.accessToken()).isEqualTo("real-jwt");
    }

    /** JWT 的角色一律是兌換當下重新解析，不是 callback 當下算好存起來的快照 */
    @Test
    void exchange_角色使用兌換當下的AuthorityResolver結果() {
        Account account = accountOf(0);
        when(tokenGenerator.hash(RAW_CODE)).thenReturn(CODE_HASH);
        when(exchangeCodes.findLookupByCodeHash(CODE_HASH)).thenReturn(Optional.of(lookupOf(0, null)));
        when(exchangeCodes.consumeIfUnused(org.mockito.ArgumentMatchers.eq(CODE_HASH), any())).thenReturn(1);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(loginPolicy.isLoginAllowed(account)).thenReturn(true);
        when(jwtUtil.generateToken(account)).thenReturn("real-jwt");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER", "EMPLOYEE"));

        OAuthExchangeResponse response = service.exchange(RAW_CODE);

        assertThat(response.roles()).containsExactly("MEMBER", "EMPLOYEE");
        verify(authorityResolver).resolveRoleNames(account);
    }

    private Account accountOf(int credentialsVersion) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
        account.setCredentialsVersion(credentialsVersion);
        return account;
    }

    private OAuthExchangeCodeLookup lookupOf(int issuedCredentialsVersion, String returnPath) {
        return new OAuthExchangeCodeLookup(ACCOUNT_ID, issuedCredentialsVersion, returnPath);
    }
}
