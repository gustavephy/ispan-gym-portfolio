package tw.com.ispan.gym.service.account.oauth;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 一次性交換碼換系統 JWT。整個方法在同一個 transaction 內，{@code code.getAccount()}
 * 的 LAZY 關聯因此可以安全存取（跟 Success Handler 不同——那裡每個 repository 呼叫都是
 * 各自獨立的短交易，account 必須 EAGER，見 OAuthTransaction 的欄位註解）。
 */
@Service
public class GoogleOAuthExchangeService {

    private static final String INVALID_MESSAGE = "交換碼無效或已過期，請重新使用 Google 登入。";

    private final OAuthExchangeCodeRepository exchangeCodes;
    private final AccountLoginPolicy loginPolicy;
    private final AccountAuthorityResolver authorityResolver;
    private final JwtUtil jwtUtil;
    private final OpaqueTokenGenerator tokenGenerator;

    public GoogleOAuthExchangeService(OAuthExchangeCodeRepository exchangeCodes, AccountLoginPolicy loginPolicy,
            AccountAuthorityResolver authorityResolver, JwtUtil jwtUtil, OpaqueTokenGenerator tokenGenerator) {
        this.exchangeCodes = exchangeCodes;
        this.loginPolicy = loginPolicy;
        this.authorityResolver = authorityResolver;
        this.jwtUtil = jwtUtil;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public OAuthExchangeResponse exchange(String rawCode) {
        String codeHash = tokenGenerator.hash(rawCode);
        OAuthExchangeCode code = exchangeCodes.findByCodeHash(codeHash)
                .orElseThrow(() -> new OAuthExchangeCodeInvalidException(INVALID_MESSAGE));

        LocalDateTime now = LocalDateTime.now();
        // 原子單次消耗：即使上面的唯讀查詢看到「還沒用過」，也要靠這個更新的列數才能
        // 確定自己真的贏得了這次兌換，不能只信任上面讀到的狀態
        int consumed = exchangeCodes.consumeIfUnused(codeHash, now);
        if (consumed == 0) {
            throw new OAuthExchangeCodeInvalidException(INVALID_MESSAGE);
        }

        // 重新執行 AccountLoginPolicy——堵住「Google callback 成功」與「前端真的呼叫兌換」
        // 之間的 TOCTOU 空隙：這段時間 Account 若被停用，兌換必須跟著失敗，不能簽出 JWT
        Account account = code.getAccount();
        if (!loginPolicy.isLoginAllowed(account)) {
            throw new OAuthExchangeCodeInvalidException(INVALID_MESSAGE);
        }

        String jwt = jwtUtil.generateToken(account);
        return new OAuthExchangeResponse(jwt, account.getId(), account.getEmail(),
                authorityResolver.resolveRoleNames(account), code.getReturnPath());
    }
}
