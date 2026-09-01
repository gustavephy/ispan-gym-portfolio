package tw.com.ispan.gym.service.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import jakarta.persistence.EntityManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tw.com.ispan.gym.dto.account.ChangePasswordRequest;
import tw.com.ispan.gym.dto.account.ChangePasswordResponse;
import tw.com.ispan.gym.dto.account.SetPasswordRequest;
import tw.com.ispan.gym.dto.account.SetPasswordResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.PasswordHistory;
import tw.com.ispan.gym.entity.account.PasswordResetToken;
import tw.com.ispan.gym.exception.CurrentPasswordIncorrectException;
import tw.com.ispan.gym.exception.PasswordAlreadyConfiguredException;
import tw.com.ispan.gym.exception.PasswordChangeRateLimitedException;
import tw.com.ispan.gym.exception.PasswordNotConfiguredException;
import tw.com.ispan.gym.exception.PasswordReusedException;
import tw.com.ispan.gym.exception.ResetTokenInvalidException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.PasswordHistoryRepository;
import tw.com.ispan.gym.repository.account.PasswordResetTokenRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;
import tw.com.ispan.gym.security.InMemoryRateLimiter;
import tw.com.ispan.gym.security.JwtUtil;

/**
 * 密碼管理：登入中修改密碼、忘記密碼申請、憑 Token 重設密碼，三者共用同一套
 * 「檢查重用歷史」「裁切 PasswordHistory」「遞增 credentialsVersion」邏輯，故合併一個
 * Service（比照 MemberRegisterService 把 register/verify/resend 放在一起的做法，
 * 這三個動作本來就是同一個「密碼生命週期」的不同階段）。
 *
 * <p>詳細規格見 memory/password_management_phase_scope.md（SEALED 2026-08-21）。
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** 最近 4 組歷史密碼 + 目前密碼＝禁止重用最近 5 組 */
    private static final int PASSWORD_HISTORY_LIMIT = 4;

    private static final int CHANGE_PASSWORD_MAX_ATTEMPTS = 5;
    private static final Duration CHANGE_PASSWORD_WINDOW = Duration.ofMinutes(15);

    private static final int FORGOT_PASSWORD_EMAIL_HOURLY_MAX = 5;
    private static final int FORGOT_PASSWORD_IP_MINUTE_MAX = 10;
    private static final int FORGOT_PASSWORD_IP_HOURLY_MAX = 30;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final PasswordHistoryRepository passwordHistories;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountAuthorityResolver authorityResolver;
    private final InMemoryRateLimiter rateLimiter;
    private final MailService mailService;
    private final Executor mailTaskExecutor;
    private final EntityManager entityManager;
    private final long resetTokenTtlMinutes;
    private final long resendCooldownSeconds;

    public PasswordService(
            AccountRepository accounts,
            PasswordHistoryRepository passwordHistories,
            PasswordResetTokenRepository passwordResetTokens,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            AccountAuthorityResolver authorityResolver,
            InMemoryRateLimiter rateLimiter,
            MailService mailService,
            @Qualifier("mailTaskExecutor") Executor mailTaskExecutor,
            EntityManager entityManager,
            @Value("${app.mail.password-reset-token-ttl-minutes}") long resetTokenTtlMinutes,
            @Value("${app.mail.password-reset-resend-cooldown-seconds}") long resendCooldownSeconds) {
        this.accounts = accounts;
        this.passwordHistories = passwordHistories;
        this.passwordResetTokens = passwordResetTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authorityResolver = authorityResolver;
        this.rateLimiter = rateLimiter;
        this.mailService = mailService;
        this.mailTaskExecutor = mailTaskExecutor;
        this.entityManager = entityManager;
        this.resetTokenTtlMinutes = resetTokenTtlMinutes;
        this.resendCooldownSeconds = resendCooldownSeconds;
    }

    // === 登入中修改密碼 =======================================================

    @Transactional
    public ChangePasswordResponse changePassword(Integer accountId, ChangePasswordRequest request) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        if (account.getPasswordHash() == null) {
            // 不能建議「改用忘記密碼」——OAuth-only 帳號依定案的純 OAuth 模型，
            // 忘記密碼流程對它們永遠不會真的寄信，那樣的提示只會把人導向一條死路
            throw new PasswordNotConfiguredException("此帳號尚未設定本機密碼，請使用原本的第三方登入方式");
        }

        // 限流檢查刻意放在 BCrypt 比對之前——已經被鎖住的請求不必再花 CPU 做雜湊比對，
        // BCrypt 本來就設計成昂貴，那份成本正是限流要保護的對象。
        String rateLimitKey = "change-password:" + accountId;
        if (!rateLimiter.tryAcquire(rateLimitKey, CHANGE_PASSWORD_MAX_ATTEMPTS, CHANGE_PASSWORD_WINDOW)) {
            throw new PasswordChangeRateLimitedException("目前密碼錯誤次數過多，請稍後再試");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), account.getPasswordHash())) {
            throw new CurrentPasswordIncorrectException("目前密碼錯誤");
        }
        // 答對當下就清空失敗紀錄——此人已經證明知道目前密碼，暴力猜測的威脅對他不再成立，
        // 之後就算新密碼被重用檢查擋下也不該保留這次的「扣打」（見 sealed spec 的討論）。
        rateLimiter.clear(rateLimitKey);

        // 只在確定要真的修改時才鎖列，BCrypt 比對階段不持有列鎖。但正因為上面那次比對是
        // 在拿鎖之前做的，取鎖期間可能有另一個並行請求已經先完成了密碼變更——locked 這裡
        // 拿到的 passwordHash 未必還是剛剛驗證過的那一份，必須用鎖定後的最新值再驗證一次，
        // 否則會出現「用已失效的舊密碼仍能覆蓋別人剛設定的新密碼」的並行漏洞。
        Account locked = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        // findByIdForUpdate 在 DB 拿到列鎖，但因為 spring.jpa.open-in-view=true，JwtAuthFilter
        // 這次請求稍早已用 findById 把同一個 Account 載入共用的 persistence context——Hibernate
        // 會回傳那個「鎖前」的舊實例，state 不是鎖定後的最新值。必須 refresh 才能讓下面所有
        // 針對 locked 的判斷（重驗目前密碼、重用檢查、credentialsVersion）看到鎖後真正的資料。
        // 根治方案（不汙染整個 request 的 persistence context）留給獨立任務評估。
        entityManager.refresh(locked);
        if (!passwordEncoder.matches(request.getCurrentPassword(), locked.getPasswordHash())) {
            throw new CurrentPasswordIncorrectException("目前密碼錯誤");
        }
        assertNotReused(locked, request.getNewPassword());

        String outgoingHash = locked.getPasswordHash();
        locked.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        locked.setCredentialsVersion(locked.getCredentialsVersion() + 1);
        recordOutgoingPasswordAndTrim(locked, outgoingHash);

        String newToken = jwtUtil.generateToken(locked);
        return new ChangePasswordResponse(newToken, locked.getId(), locked.getEmail(),
                authorityResolver.resolveRoleNames(locked));
    }

    // === OAuth-only 帳號設定第一組本機密碼 =====================================

    /**
     * OAuth-only 帳號（{@code passwordHash == null}）設定它的第一組本機密碼。
     *
     * <p>刻意不做 {@code changePassword()} 後半段的三件事，因為它們在「第一組密碼」情境下
     * 沒有意義或無法執行：
     * <ul>
     *   <li>不比對 currentPassword、不套 currentPassword 的 reserve-then-clear 限流——
     *       這種帳號根本沒有目前密碼，沒有密碼猜測面。</li>
     *   <li>不呼叫 {@code assertNotReused()}——目前密碼是 null，無從比對。</li>
     *   <li>不呼叫 {@code recordOutgoingPasswordAndTrim()}——沒有「被取代的舊密碼」可以寫入歷史。</li>
     * </ul>
     *
     * <p>沿用的是帳號安全域固定的部分：Account 悲觀寫鎖、{@code credentialsVersion + 1}
     * 讓 OAuth 登入簽發的所有舊 JWT 失效、以遞增後版本簽發新 JWT 回傳。
     *
     * <p>併發：兩個請求同時進來時 Account 鎖序列化它們——第一個成功設定，第二個取得鎖之後
     * 看到 {@code passwordHash != null}，穩定回 {@link PasswordAlreadyConfiguredException}，
     * 不覆寫、也不再次遞增版本。判斷「能不能設定」只看 {@code passwordHash == null}，是唯一依據。
     */
    @Transactional
    public SetPasswordResponse setPassword(Integer accountId, SetPasswordRequest request) {
        Account locked = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        // 見 changePassword() 的同段說明：OSIV + JwtAuthFilter 的預載會讓 locked 是鎖前的
        // 舊實例。沒有這行 refresh，兩個併發請求都會看到 passwordHash == null 而各自設定一次，
        // 悲觀鎖只序列化了交易、擋不住「重複設定第一組密碼」。
        entityManager.refresh(locked);
        if (locked.getPasswordHash() != null) {
            throw new PasswordAlreadyConfiguredException("此帳號已設定本機密碼，如需變更請使用「修改密碼」");
        }

        locked.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        locked.setCredentialsVersion(locked.getCredentialsVersion() + 1);

        String newToken = jwtUtil.generateToken(locked);
        return new SetPasswordResponse(newToken, locked.getId(), locked.getEmail(),
                authorityResolver.resolveRoleNames(locked));
    }

    // === 忘記密碼申請 =========================================================

    /**
     * 不論 Email 是否存在、是否為 OAuth-only、是否被限流，一律不對外揭露差異——
     * Controller 永遠回同一句話，這裡只負責「該做的時候才真的做」。
     */
    @Transactional
    public void requestPasswordReset(String rawEmail, String clientIp) {
        String normalizedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!withinForgotPasswordRateLimits(normalizedEmail, clientIp)) {
            return;
        }
        accounts.findByEmailIgnoreCase(normalizedEmail)
                .filter(a -> a.getDeletedAt() == null && a.getPasswordHash() != null)
                .ifPresent(account -> issueResetToken(account, normalizedEmail));
    }

    /**
     * IP 側先查、email 側後查：IP 值域天生有限，一旦 IP 已經超標就直接回絕，
     * 不再替任何 email 建立新的限流 bucket。這不只是省事，是必要的防線——
     * 如果不論 IP 狀態一律都替 email 建 bucket，同一個 IP 換無數個不存在的隨機
     * email 狂送，就能無限撐大 InMemoryRateLimiter 內部的 map，形成記憶體耗盡攻擊；
     * 反過來 email 那三個門檻互相之間仍然全部各自消耗一次名額（同一個 email 的
     * cooldown 與每小時上限是各自獨立的限制，不能因為其中一個先超標就跳過另一個）。
     */
    private boolean withinForgotPasswordRateLimits(String normalizedEmail, String clientIp) {
        boolean ipMinuteOk = rateLimiter.tryAcquire("forgot-password:ip-minute:" + clientIp,
                FORGOT_PASSWORD_IP_MINUTE_MAX, Duration.ofMinutes(1));
        boolean ipHourlyOk = rateLimiter.tryAcquire("forgot-password:ip-hourly:" + clientIp,
                FORGOT_PASSWORD_IP_HOURLY_MAX, Duration.ofHours(1));
        if (!ipMinuteOk || !ipHourlyOk) {
            return false;
        }

        String emailHash = sha256(normalizedEmail);
        boolean emailCooldownOk = rateLimiter.tryAcquire("forgot-password:email-cooldown:" + emailHash,
                1, Duration.ofSeconds(resendCooldownSeconds));
        boolean emailHourlyOk = rateLimiter.tryAcquire("forgot-password:email-hourly:" + emailHash,
                FORGOT_PASSWORD_EMAIL_HOURLY_MAX, Duration.ofHours(1));
        return emailCooldownOk & emailHourlyOk;
    }

    private void issueResetToken(Account account, String sendTo) {
        LocalDateTime now = LocalDateTime.now();
        // 新申請一律讓同帳號其他仍有效的舊 token 作廢，避免使用者混用到不是最新的那封信
        passwordResetTokens.findByAccountIdAndUsedAtIsNullAndInvalidatedAtIsNull(account.getId())
                .forEach(t -> t.setInvalidatedAt(now));

        String rawToken = generateRawToken();
        PasswordResetToken entity = new PasswordResetToken();
        entity.setAccount(account);
        entity.setTokenHash(sha256(rawToken));
        entity.setExpiresAt(now.plusMinutes(resetTokenTtlMinutes));
        passwordResetTokens.save(entity);

        // 一定要等 transaction 真的 commit 才寄信：commit 前寄出的話，萬一之後 rollback，
        // 使用者會拿到一個看起來有效、實際上資料庫裡不存在的連結。非同步丟給 mailTaskExecutor，
        // 讓忘記密碼的回應不必等 SMTP，也不會讓「帳號存在」比「帳號不存在」明顯慢。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailTaskExecutor.execute(() -> {
                        try {
                            mailService.sendPasswordResetEmail(sendTo, rawToken, resetTokenTtlMinutes);
                        } catch (RuntimeException ex) {
                            // 寄信本身失敗（例如 SMTP 逾時）：只記警告，不能回頭影響
                            // 已經回給前端的 202——這條路徑本來就不揭露結果
                            log.warn("密碼重設信寄送失敗（account id={}）", account.getId(), ex);
                        }
                    });
                } catch (RuntimeException ex) {
                    // execute() 本身也可能直接拋例外（例如佇列已滿的 TaskRejectedException），
                    // 跟上面「任務已經開始跑才失敗」是不同的失敗點，必須分開接——這裡已經是
                    // commit 之後才觸發，絕不能讓它意外把一個已經成功的操作變成前端看到的錯誤
                    log.warn("密碼重設信排入寄送佇列失敗（account id={}）", account.getId(), ex);
                }
            }
        });
    }

    // === 憑 Token 重設密碼 =====================================================

    private static final String RESET_TOKEN_INVALID_MESSAGE = "重設連結無效或已過期，請重新申請。";

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);

        // 帳號安全域統一採「先鎖 Account、再鎖短效憑證」的順序。
        // Password Reset 與 Google OAuth 目前操作不同的憑證表，沒有已確認的跨功能
        // deadlock；這裡調整為 Account-first 是為了建立一致的鎖定慣例，降低未來
        // 新增跨域流程時形成相反鎖序的風險。這裡一開始只有 token，還不知道
        // accountId，所以先用不鎖的 scalar 查詢定位——只拿 accountId，刻意不把整個
        // Entity 查出來放進 persistence context，否則下面 findByTokenHashForUpdate
        // 即使真的下了鎖定查詢，Hibernate 的 identity map 仍可能回傳同一個舊 instance，
        // 讓「鎖內重新驗證」形同虛設。這次讀取結果不能當有效性或授權判斷，所有狀態
        // 都必須在下面取得鎖之後重新驗證一次。
        Integer accountId = passwordResetTokens.findAccountIdByTokenHash(tokenHash)
                .orElseThrow(() -> new ResetTokenInvalidException(RESET_TOKEN_INVALID_MESSAGE));

        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResetTokenInvalidException(RESET_TOKEN_INVALID_MESSAGE));

        PasswordResetToken token = passwordResetTokens.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new ResetTokenInvalidException(RESET_TOKEN_INVALID_MESSAGE));

        LocalDateTime now = LocalDateTime.now();
        // 鎖內複查：定位時看到的 token 可能在鎖 Account 期間被其他請求使用／作廢／
        // 剛好跨過期限，且定位到的 accountId 理論上不該跟鎖定後的 token 所屬帳號不同
        // （正常流程不會發生，但明確擋一次，不假設兩次查詢之間資料不會變動）。
        if (token.getUsedAt() != null || token.getInvalidatedAt() != null || !token.getExpiresAt().isAfter(now)
                || !token.getAccount().getId().equals(accountId)) {
            throw new ResetTokenInvalidException(RESET_TOKEN_INVALID_MESSAGE);
        }
        // 防禦性複查：帳號可能在 token 核發後被軟刪除，或（理論上不會發生，但明確擋一次）
        // 已經不是本機密碼帳號——這兩種狀態都不該讓重設繼續，公開錯誤一律一樣
        if (account.getDeletedAt() != null || account.getPasswordHash() == null) {
            throw new ResetTokenInvalidException(RESET_TOKEN_INVALID_MESSAGE);
        }

        assertNotReused(account, newPassword);

        String outgoingHash = account.getPasswordHash();
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setCredentialsVersion(account.getCredentialsVersion() + 1);
        recordOutgoingPasswordAndTrim(account, outgoingHash);

        token.setUsedAt(now);
        passwordResetTokens.findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(account.getId(), token.getId())
                .forEach(t -> t.setInvalidatedAt(now));
        // 刻意不簽發新 JWT——重設密碼視為「帳號可能已遭他人取得」的復原流程，
        // 一律要求以新密碼重新登入，而不是延續一個未必是本人的既有工作階段。
    }

    // === 共用：重用檢查、歷史裁切 =============================================

    private void assertNotReused(Account account, String rawNewPassword) {
        if (passwordEncoder.matches(rawNewPassword, account.getPasswordHash())) {
            throw new PasswordReusedException("新密碼不得與目前密碼相同");
        }
        List<PasswordHistory> recent = passwordHistories
                .findTop4ByAccountIdOrderByCreatedAtDescIdDesc(account.getId());
        for (PasswordHistory history : recent) {
            if (passwordEncoder.matches(rawNewPassword, history.getPasswordHash())) {
                throw new PasswordReusedException("新密碼不得與近期使用過的密碼相同");
            }
        }
    }

    /** 把「即將被取代」的密碼寫入歷史，並把該帳號的歷史裁切回最多 4 筆 */
    private void recordOutgoingPasswordAndTrim(Account account, String outgoingHash) {
        PasswordHistory entry = new PasswordHistory();
        entry.setAccount(account);
        entry.setPasswordHash(outgoingHash);
        passwordHistories.save(entry);

        List<PasswordHistory> all = passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(account.getId());
        if (all.size() > PASSWORD_HISTORY_LIMIT) {
            passwordHistories.deleteAll(all.subList(PASSWORD_HISTORY_LIMIT, all.size()));
        }
    }

    // === Token 工具 ============================================================

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是每個 JVM 都必須內建的演算法（JLS 保證），不會真的發生
            throw new IllegalStateException("SHA-256 不存在", ex);
        }
    }
}
