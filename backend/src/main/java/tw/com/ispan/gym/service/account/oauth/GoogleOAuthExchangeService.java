package tw.com.ispan.gym.service.account.oauth;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 一次性交換碼換系統 JWT。
 *
 * <p>先用非鎖定的 {@link OAuthExchangeCodeLookup} 純量投影定位 accountId／核發當下的
 * {@code issuedCredentialsVersion}，再用 {@code findByIdForUpdate} 鎖 Account——跟
 * LOGIN／LINK／{@code PasswordService} 同一套 Account-first 鎖序（見
 * docs/google-oauth-design-notes.md）。鎖進來之後比對「目前版本」與「核發當下版本」
 * 是否相同：核發之後、兌換之前如果發生 UNLINK／改密碼／全裝置登出，credentialsVersion
 * 會遞增，版本不相同就代表這張 Code 是在一個已經被撤銷的狀態下核發的，即使 Code 本身
 * 還沒過期也必須拒絕——這是真正堵住「舊 Code 在安全事件後還能換出新 JWT」的關卡（2026-08-22
 * code review 補上；重新執行 {@link AccountLoginPolicy} 只擋得住帳號被停用，擋不住
 * credentialsVersion 這種「同一個 Account 仍然活著、但這個特定登入事件已經被撤銷」的情況）。
 *
 * <p>原子消耗（{@code consumeIfUnused}）維持在鎖定與版本比對之前執行，理由是效率：
 * 併發重放同一個 Code 的請求，讓消耗這一步先篩掉多數注定失敗的請求，勝出的那個才需要
 * 真的去鎖 Account、比對版本——鎖序本身的正確性不受消耗發生在鎖前或鎖後影響，因為
 * 「是否真的核發過 JWT」只看下面有沒有走到 {@code jwtUtil.generateToken}，消耗失敗
 * 一律不會走到那裡。
 */
@Service
public class GoogleOAuthExchangeService {

    private static final String INVALID_MESSAGE = "交換碼無效或已過期，請重新使用 Google 登入。";

    private final OAuthExchangeCodeRepository exchangeCodes;
    private final AccountRepository accounts;
    private final AccountLoginPolicy loginPolicy;
    private final AccountAuthorityResolver authorityResolver;
    private final JwtUtil jwtUtil;
    private final OpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    public GoogleOAuthExchangeService(OAuthExchangeCodeRepository exchangeCodes, AccountRepository accounts,
            AccountLoginPolicy loginPolicy, AccountAuthorityResolver authorityResolver, JwtUtil jwtUtil,
            OpaqueTokenGenerator tokenGenerator, Clock clock) {
        this.exchangeCodes = exchangeCodes;
        this.accounts = accounts;
        this.loginPolicy = loginPolicy;
        this.authorityResolver = authorityResolver;
        this.jwtUtil = jwtUtil;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    @Transactional
    public OAuthExchangeResponse exchange(String rawCode) {
        String codeHash = tokenGenerator.hash(rawCode);
        OAuthExchangeCodeLookup lookup = exchangeCodes.findLookupByCodeHash(codeHash)
                .orElseThrow(() -> new OAuthExchangeCodeInvalidException(INVALID_MESSAGE));

        LocalDateTime now = LocalDateTime.now(clock);
        // 原子單次消耗：即使上面的唯讀查詢看到「還沒用過」，也要靠這個更新的列數才能
        // 確定自己真的贏得了這次兌換，不能只信任上面讀到的狀態
        int consumed = exchangeCodes.consumeIfUnused(codeHash, now);
        if (consumed == 0) {
            throw new OAuthExchangeCodeInvalidException(INVALID_MESSAGE);
        }

        Account account = accounts.findByIdForUpdate(lookup.accountId())
                .orElseThrow(() -> new OAuthExchangeCodeInvalidException(INVALID_MESSAGE));
        if (account.getCredentialsVersion() != lookup.issuedCredentialsVersion()) {
            throw new OAuthExchangeCodeInvalidException(INVALID_MESSAGE);
        }
        // 重新執行 AccountLoginPolicy——堵住「Google callback 成功」與「前端真的呼叫兌換」
        // 之間的 TOCTOU 空隙：這段時間 Account 若被停用，兌換必須跟著失敗，不能簽出 JWT
        if (!loginPolicy.isLoginAllowed(account)) {
            throw new OAuthExchangeCodeInvalidException(INVALID_MESSAGE);
        }

        String jwt = jwtUtil.generateToken(account);
        return new OAuthExchangeResponse(jwt, account.getId(), account.getEmail(),
                authorityResolver.resolveRoleNames(account), lookup.returnPath());
    }
}
