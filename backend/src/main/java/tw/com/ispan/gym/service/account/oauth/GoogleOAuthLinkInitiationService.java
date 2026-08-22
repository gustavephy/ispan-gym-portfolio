package tw.com.ispan.gym.service.account.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthTransaction;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.exception.OAuthAlreadyLinkedException;
import tw.com.ispan.gym.exception.ReauthenticationRequiredException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthTransactionRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 發起 Google LINK：密碼重新驗證 → Account-first 悲觀鎖 → supersede 舊 PENDING LINK
 * transaction → 建立新的一筆。Callback 階段的業務判定在 {@link GoogleOAuthLinkService}，
 * 這裡只負責「有沒有資格開始一次 LINK 流程」。
 *
 * <p>鎖序固定 Account → OAuthTransaction，跟 {@code PasswordService}／
 * {@code GoogleOAuthRegistrationCreationService} 走同一套帳號安全域全域鎖序
 * （見 memory/google_oauth_phase_scope.md）。account-lock 本身就序列化了同一
 * Account 的並行發起請求，所以「同一 Account 同時最多一筆 PENDING LINK」不是靠
 * DB 唯一索引僥倖擋住，而是鎖本身結構性保證——索引只是最後一道防線。
 *
 * <p>密碼重新驗證沿用 {@code PasswordService.changePassword()} 已經驗證過的模式：
 * reserve-then-clear 的 rate limit（比對 BCrypt 前先佔位，避免鎖住的請求還要
 * 白花 BCrypt 成本）、鎖前鎖後各驗證一次密碼（鎖後那次擋掉「驗證通過到取得鎖之間
 * 密碼被別的請求改掉」的併發覆寫）。key 用專屬前綴（{@code oauth-link-reauth:}）
 * 與 change-password 的節流各自獨立，不共用同一組計數。
 */
@Service
public class GoogleOAuthLinkInitiationService {

    private static final int REAUTH_MAX_ATTEMPTS = 5;
    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(15);

    private final AccountRepository accounts;
    private final OauthAccountRepository oauthAccounts;
    private final OAuthTransactionRepository transactions;
    private final PasswordEncoder passwordEncoder;
    private final InMemoryRateLimiter rateLimiter;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    public GoogleOAuthLinkInitiationService(AccountRepository accounts, OauthAccountRepository oauthAccounts,
            OAuthTransactionRepository transactions, PasswordEncoder passwordEncoder,
            InMemoryRateLimiter rateLimiter, OpaqueTokenGenerator tokenGenerator, GoogleOAuthProperties properties,
            Clock clock) {
        this.accounts = accounts;
        this.oauthAccounts = oauthAccounts;
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 一次性 state（原始值）；前端導向 {@code /oauth2/authorization/google?transaction=<這個值>} */
    @Transactional
    public String initiate(Integer accountId, String currentPassword) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        if (account.getPasswordHash() == null) {
            throw new ReauthenticationRequiredException("此帳號尚未設定本機密碼，無法重新驗證身分");
        }

        String rateLimitKey = "oauth-link-reauth:" + accountId;
        if (!rateLimiter.tryAcquire(rateLimitKey, REAUTH_MAX_ATTEMPTS, REAUTH_WINDOW)) {
            throw new ReauthenticationRequiredException("密碼錯誤次數過多，請稍後再試");
        }
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ReauthenticationRequiredException("目前密碼錯誤");
        }
        // 答對當下即清空失敗紀錄——理由與 PasswordService.changePassword() 相同：
        // 已證明知道目前密碼，之後若因「已綁定」等業務規則被拒，不該保留這次的「扣打」
        rateLimiter.clear(rateLimitKey);

        Account locked = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        if (!passwordEncoder.matches(currentPassword, locked.getPasswordHash())) {
            throw new ReauthenticationRequiredException("目前密碼錯誤");
        }
        if (oauthAccounts.findByAccountIdAndOauthProvider(accountId, OAuthProvider.GOOGLE).isPresent()) {
            throw new OAuthAlreadyLinkedException("此帳號已經綁定一個 Google 帳號，請先解除再重新綁定");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 同一 Account 的舊 PENDING LINK 直接取代——使用者可能是關掉前一個 Google 分頁
        // 後重新點擊，不是攻擊；舊分頁回來時會看到 SUPERSEDED，導向「請重新發起」
        transactions.supersedePendingLinkTransactions(accountId, now);

        String rawState = tokenGenerator.generate();
        OAuthTransaction transaction = new OAuthTransaction();
        transaction.setStateHash(tokenGenerator.hash(rawState));
        transaction.setOauthProvider(OAuthProvider.GOOGLE);
        transaction.setPurpose(OAuthTransactionPurpose.LINK);
        transaction.setAccount(locked);
        transaction.setExpiresAt(now.plus(properties.transactionTtl()));
        transactions.save(transaction);

        return rawState;
    }
}
