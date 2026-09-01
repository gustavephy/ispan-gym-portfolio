package tw.com.ispan.gym.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tw.com.ispan.gym.dto.account.ChangePasswordRequest;
import tw.com.ispan.gym.dto.account.ChangePasswordResponse;
import tw.com.ispan.gym.dto.account.SetPasswordRequest;
import tw.com.ispan.gym.dto.account.SetPasswordResponse;
import tw.com.ispan.gym.exception.PasswordAlreadyConfiguredException;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.PasswordHistory;
import tw.com.ispan.gym.entity.account.PasswordResetToken;
import tw.com.ispan.gym.exception.CurrentPasswordIncorrectException;
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
 * 純 Mockito 單元測試（比照 AdminEmployeeServiceTest），不連資料庫、不開 Spring context。
 * 只驗證 Service 有沒有把並行覆寫防護、重用檢查、歷史裁切、credentialsVersion 遞增、
 * 限流互動這些規則做對；查不查得到、SHA-256 雜湊是否真的不可逆——那些留給真實 DB E2E。
 *
 * <p>requestPasswordReset() 內部用 TransactionSynchronizationManager.registerSynchronization()
 * 延後到 commit 後才寄信，沒有真實 Spring 交易的情境下必須手動用 initSynchronization()／
 * 逐一觸發 afterCommit()／clearSynchronization() 模擬，否則會直接丟
 * IllegalStateException（transaction synchronization is not active）。
 */
class PasswordServiceTest {

    private static final Integer ACCOUNT_ID = 30;
    private static final long TOKEN_TTL_MINUTES = 15L;
    private static final long RESEND_COOLDOWN_SECONDS = 60L;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordHistoryRepository passwordHistories = mock(PasswordHistoryRepository.class);
    private final PasswordResetTokenRepository passwordResetTokens = mock(PasswordResetTokenRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AccountAuthorityResolver authorityResolver = mock(AccountAuthorityResolver.class);
    private final InMemoryRateLimiter rateLimiter = mock(InMemoryRateLimiter.class);
    private final MailService mailService = mock(MailService.class);
    private final Executor mailTaskExecutor = mock(Executor.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);

    private final PasswordService service = new PasswordService(
            accounts, passwordHistories, passwordResetTokens, passwordEncoder, jwtUtil,
            authorityResolver, rateLimiter, mailService, mailTaskExecutor, entityManager,
            TOKEN_TTL_MINUTES, RESEND_COOLDOWN_SECONDS);

    private Account accountOf(String passwordHash) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("wendy-test@local.test");
        account.setPasswordHash(passwordHash);
        account.setCredentialsVersion(2);
        return account;
    }

    private ChangePasswordRequest changeRequest(String currentPassword, String newPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(currentPassword);
        request.setNewPassword(newPassword);
        request.setConfirmPassword(newPassword);
        return request;
    }

    /** 允許限流通過，所有 tryAcquire 呼叫一律回 true（除非個別測試另外覆寫） */
    private void rateLimiterAlwaysAllows() {
        when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
    }

    /**
     * 模擬 {@code entityManager.refresh(account)}：把傳入的 Account 的 passwordHash／
     * credentialsVersion 就地更新成「鎖定後從 DB 讀到的最新值」。重現 OSIV + JwtAuthFilter
     * 下 findByIdForUpdate 拿到的是鎖前舊實例、必須 refresh 才看得到並行請求已 commit 的變更。
     */
    private void refreshUpdatesTo(String freshPasswordHash, int freshCredentialsVersion) {
        doAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setPasswordHash(freshPasswordHash);
            a.setCredentialsVersion(freshCredentialsVersion);
            return null;
        }).when(entityManager).refresh(any(Account.class));
    }

    /** 模擬「commit 後才觸發」的 TransactionSynchronization——沒有真實交易時必須手動跑一次 */
    private void runWithSimulatedCommit(Runnable action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // === changePassword()：帳號狀態閘門 =========================================

    @Test
    void changePassword_OAuthOnly帳號丟PasswordNotConfigured_且不呼叫限流() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf(null)));

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("x", "Newpass@2026")))
                .isInstanceOf(PasswordNotConfiguredException.class);
        verify(rateLimiter, never()).tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void changePassword_帳號不存在丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("x", "Newpass@2026")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // === changePassword()：限流 =================================================

    @Test
    void changePassword_已達限流上限丟RateLimited_且不做BCrypt比對() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hashed-current")));
        when(rateLimiter.tryAcquire(eq("change-password:" + ACCOUNT_ID), eq(5), eq(Duration.ofMinutes(15))))
                .thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("x", "Newpass@2026")))
                .isInstanceOf(PasswordChangeRateLimitedException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // === changePassword()：目前密碼驗證（含拿鎖後複驗） ==========================

    @Test
    void changePassword_目前密碼錯誤丟CurrentPasswordIncorrect() {
        Account account = accountOf("hashed-current");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("wrong", "hashed-current")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("wrong", "Newpass@2026")))
                .isInstanceOf(CurrentPasswordIncorrectException.class);
        verify(accounts, never()).findByIdForUpdate(any());
    }

    @Test
    void changePassword_答對目前密碼後清空限流紀錄() {
        Account account = accountOf("hashed-current");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "hashed-current")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(jwtUtil.generateToken(account)).thenReturn("new-token");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        service.changePassword(ACCOUNT_ID, changeRequest("correct", "Newpass@2026"));

        verify(rateLimiter).clear("change-password:" + ACCOUNT_ID);
    }

    /**
     * 並行覆寫漏洞的回歸測試（code review 抓到的原始 bug + 2026-09-01 真實 DB E2E 抓到的
     * 「悲觀鎖被 OSIV stale entity 打穿」）：findById 與 findByIdForUpdate 回傳「同一個」鎖前
     * 舊實例（重現 persistence context 汙染），是 {@code entityManager.refresh()} 把 passwordHash
     * 更新成並行請求剛 commit 的新值——拿鎖後的重新驗證因此會用新 hash 比對、擋下舊密碼，
     * 不會覆寫別人剛設定的密碼。
     */
    @Test
    void changePassword_拿鎖後refresh讀到並行請求已改的密碼_用舊密碼重驗被擋下() {
        Account staleInstance = accountOf("stale-hash");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(staleInstance));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(staleInstance));
        rateLimiterAlwaysAllows();
        // refresh 把 stale 實例更新成「並行請求剛設定的新 hash」
        refreshUpdatesTo("fresher-hash-set-by-another-request", 2);
        // 拿鎖前用舊 hash 比對能過；refresh 之後用新 hash 比對就不該再過
        when(passwordEncoder.matches("stale-current-password", "stale-hash")).thenReturn(true);
        when(passwordEncoder.matches("stale-current-password", "fresher-hash-set-by-another-request"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID,
                changeRequest("stale-current-password", "Newpass@2026")))
                .isInstanceOf(CurrentPasswordIncorrectException.class);

        // 沒有覆寫掉另一個請求剛設定的密碼
        assertThat(staleInstance.getPasswordHash()).isEqualTo("fresher-hash-set-by-another-request");
        verify(passwordHistories, never()).save(any());
    }

    /** refresh 必須在拿鎖之後、任何針對 locked 的業務判斷（重驗目前密碼）之前 */
    @Test
    void changePassword_refresh順序_在findByIdForUpdate之後_在重驗目前密碼之前() {
        Account account = accountOf("hashed-current");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "hashed-current")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(jwtUtil.generateToken(account)).thenReturn("t");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        service.changePassword(ACCOUNT_ID, changeRequest("correct", "Newpass@2026"));

        InOrder inOrder = inOrder(accounts, entityManager, passwordEncoder);
        inOrder.verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        inOrder.verify(entityManager).refresh(account);
        // refresh 之後才是「拿鎖後重驗目前密碼」那一次 matches
        inOrder.verify(passwordEncoder).matches("correct", "hashed-current");
    }

    // === changePassword()：新密碼重用檢查 =======================================

    @Test
    void changePassword_新密碼等於目前密碼丟PasswordReused() {
        Account account = accountOf("hashed-current");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "hashed-current")).thenReturn(true);
        // 重用檢查對「目前密碼」再比對一次，這裡也回 true
        when(passwordEncoder.matches("SamePass@2026", "hashed-current")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("correct", "SamePass@2026")))
                .isInstanceOf(PasswordReusedException.class);
    }

    @Test
    void changePassword_新密碼等於近期歷史密碼丟PasswordReused() {
        Account account = accountOf("hashed-current");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "hashed-current")).thenReturn(true);
        when(passwordEncoder.matches("OldPass@2026", "hashed-current")).thenReturn(false);

        PasswordHistory history = new PasswordHistory();
        history.setPasswordHash("old-history-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID))
                .thenReturn(List.of(history));
        when(passwordEncoder.matches("OldPass@2026", "old-history-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, changeRequest("correct", "OldPass@2026")))
                .isInstanceOf(PasswordReusedException.class);
    }

    // === changePassword()：成功路徑 =============================================

    @Test
    void changePassword_成功時寫入歷史遞增版本並回傳新Token() {
        Account account = accountOf("old-hash");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("Newpass@2026", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("Newpass@2026")).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(jwtUtil.generateToken(account)).thenReturn("brand-new-token");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        ChangePasswordResponse response = service.changePassword(ACCOUNT_ID, changeRequest("correct", "Newpass@2026"));

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.getCredentialsVersion()).isEqualTo(3); // accountOf() 起始值是 2
        assertThat(response.accessToken()).isEqualTo("brand-new-token");
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.roles()).containsExactly("MEMBER");

        ArgumentCaptor<PasswordHistory> historyCaptor = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(passwordHistories).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void changePassword_歷史超過4筆時裁切多餘的舊紀錄() {
        Account account = accountOf("old-hash");
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        rateLimiterAlwaysAllows();
        when(passwordEncoder.matches("correct", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("Newpass@2026", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());

        List<PasswordHistory> fiveRows = List.of(
                historyRow(5), historyRow(4), historyRow(3), historyRow(2), historyRow(1));
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(fiveRows);
        when(jwtUtil.generateToken(account)).thenReturn("t");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of());

        service.changePassword(ACCOUNT_ID, changeRequest("correct", "Newpass@2026"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PasswordHistory>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(passwordHistories).deleteAll(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue()).hasSize(1);
        assertThat(deleteCaptor.getValue().get(0).getId()).isEqualTo(1);
    }

    private PasswordHistory historyRow(int id) {
        PasswordHistory history = new PasswordHistory();
        history.setId(id);
        history.setPasswordHash("hash-" + id);
        return history;
    }

    // === setPassword()：OAuth-only 設定第一組本機密碼（第五批）===================

    private SetPasswordRequest setRequest(String newPassword) {
        SetPasswordRequest request = new SetPasswordRequest();
        request.setNewPassword(newPassword);
        request.setConfirmPassword(newPassword);
        return request;
    }

    @Test
    void setPassword_OAuthOnly帳號成功_直接鎖Account_遞增版本_回傳新Token_不寫歷史() {
        Account account = accountOf(null); // passwordHash == null → OAuth-only
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode("Newpass@2026")).thenReturn("first-hash");
        when(jwtUtil.generateToken(account)).thenReturn("brand-new-token");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER"));

        SetPasswordResponse response = service.setPassword(ACCOUNT_ID, setRequest("Newpass@2026"));

        assertThat(account.getPasswordHash()).isEqualTo("first-hash");
        assertThat(account.getCredentialsVersion()).isEqualTo(3); // accountOf() 起始值是 2
        assertThat(response.accessToken()).isEqualTo("brand-new-token");
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.roles()).containsExactly("MEMBER");

        // 拿鎖後一定 refresh，才用鎖後最新的 passwordHash 判斷
        verify(entityManager).refresh(account);
        // 沒有 currentPassword 可驗、沒有舊密碼可寫入歷史、沒有 reuse 檢查、不套限流
        verify(accounts, never()).findById(any());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(passwordHistories, never()).save(any());
        verify(rateLimiter, never()).tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** refresh 必須在拿鎖之後、「passwordHash != null」判斷之前 */
    @Test
    void setPassword_refresh順序_在findByIdForUpdate之後_在已設定判斷之前() {
        Account account = accountOf(null);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordEncoder.encode(any())).thenReturn("first-hash");
        when(jwtUtil.generateToken(account)).thenReturn("t");
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of());

        service.setPassword(ACCOUNT_ID, setRequest("Newpass@2026"));

        InOrder inOrder = inOrder(accounts, entityManager, passwordEncoder);
        inOrder.verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        inOrder.verify(entityManager).refresh(account);
        inOrder.verify(passwordEncoder).encode("Newpass@2026"); // 判斷通過後才會走到 encode
    }

    @Test
    void setPassword_已有密碼時丟PasswordAlreadyConfigured_不覆寫_不遞增版本() {
        Account account = accountOf("existing-hash");
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.setPassword(ACCOUNT_ID, setRequest("Newpass@2026")))
                .isInstanceOf(PasswordAlreadyConfiguredException.class);

        assertThat(account.getPasswordHash()).isEqualTo("existing-hash");
        assertThat(account.getCredentialsVersion()).isEqualTo(2);
        verify(passwordEncoder, never()).encode(any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    @Test
    void setPassword_帳號不存在丟ResourceNotFound() {
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPassword(ACCOUNT_ID, setRequest("Newpass@2026")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * 併發（2026-09-01 真實 DB E2E 抓到的核心情境）：findByIdForUpdate 因 OSIV stale entity
     * 拿到的是鎖前舊實例（passwordHash 仍是 null），是 {@code entityManager.refresh()} 讓它看到
     * 並行請求剛 commit 的 passwordHash。沒有 refresh，這個請求會誤以為自己是第一個而重複設定；
     * 有 refresh 後，穩定回 PASSWORD_ALREADY_CONFIGURED，不遞增 cv、不簽 JWT、不覆寫密碼。
     */
    @Test
    void setPassword_拿鎖後refresh讀到並行請求已設定的密碼_回PasswordAlreadyConfigured() {
        Account staleInstance = accountOf(null); // 鎖前舊實例：passwordHash 還是 null
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(staleInstance));
        // refresh 把它更新成「並行請求剛 commit 的狀態」：已有密碼、版本已 +1
        refreshUpdatesTo("hash-set-by-concurrent-request", 3);

        assertThatThrownBy(() -> service.setPassword(ACCOUNT_ID, setRequest("Newpass@2026")))
                .isInstanceOf(PasswordAlreadyConfiguredException.class);

        assertThat(staleInstance.getPasswordHash()).isEqualTo("hash-set-by-concurrent-request");
        assertThat(staleInstance.getCredentialsVersion()).isEqualTo(3); // 沒有再 +1
        verify(passwordEncoder, never()).encode(any());
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    // === requestPasswordReset()：限流閘門 =======================================

    @Test
    void requestPasswordReset_IP每分鐘超標時不查帳號() {
        when(rateLimiter.tryAcquire(eq("forgot-password:ip-minute:1.2.3.4"), eq(10), eq(Duration.ofMinutes(1))))
                .thenReturn(false);

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        verify(accounts, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void requestPasswordReset_IP每分鐘通過但每小時超標時不建立Email限流bucket() {
        when(rateLimiter.tryAcquire(eq("forgot-password:ip-minute:1.2.3.4"), eq(10), eq(Duration.ofMinutes(1))))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(eq("forgot-password:ip-hourly:1.2.3.4"), eq(30), eq(Duration.ofHours(1))))
                .thenReturn(false);

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        verify(rateLimiter, never()).tryAcquire(org.mockito.ArgumentMatchers.contains("forgot-password:email"),
                org.mockito.ArgumentMatchers.anyInt(), any());
        verify(accounts, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void requestPasswordReset_Email冷卻中時不查帳號() {
        rateLimiterIpAllows("1.2.3.4");
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.contains("forgot-password:email-cooldown:"),
                eq(1), eq(Duration.ofSeconds(60)))).thenReturn(false);

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        verify(accounts, never()).findByEmailIgnoreCase(any());
    }

    private void rateLimiterIpAllows(String ip) {
        when(rateLimiter.tryAcquire(eq("forgot-password:ip-minute:" + ip), eq(10), eq(Duration.ofMinutes(1))))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(eq("forgot-password:ip-hourly:" + ip), eq(30), eq(Duration.ofHours(1))))
                .thenReturn(true);
    }

    private void rateLimiterEmailAllows() {
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.contains("forgot-password:email-cooldown:"),
                eq(1), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.contains("forgot-password:email-hourly:"),
                eq(5), eq(Duration.ofHours(1)))).thenReturn(true);
    }

    // === requestPasswordReset()：防列舉——不合格帳號一律靜默跳過 ==================

    @Test
    void requestPasswordReset_Email不存在時不建立Token也不寄信() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        when(accounts.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        runWithSimulatedCommit(() -> service.requestPasswordReset("nobody@example.com", "1.2.3.4"));

        verify(passwordResetTokens, never()).save(any());
        verify(mailTaskExecutor, never()).execute(any());
    }

    @Test
    void requestPasswordReset_OAuthOnly帳號不建立Token也不寄信() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        when(accounts.findByEmailIgnoreCase("wendy-test@local.test")).thenReturn(Optional.of(accountOf(null)));

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        verify(passwordResetTokens, never()).save(any());
        verify(mailTaskExecutor, never()).execute(any());
    }

    @Test
    void requestPasswordReset_已軟刪除帳號不建立Token() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        Account deleted = accountOf("hash");
        deleted.softDelete();
        when(accounts.findByEmailIgnoreCase("wendy-test@local.test")).thenReturn(Optional.of(deleted));

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        verify(passwordResetTokens, never()).save(any());
    }

    @Test
    void requestPasswordReset_Email正規化為小寫後查詢() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        when(accounts.findByEmailIgnoreCase("wendy-test@local.test")).thenReturn(Optional.empty());

        runWithSimulatedCommit(() -> service.requestPasswordReset("  Wendy-Test@Local.TEST  ", "1.2.3.4"));

        verify(accounts).findByEmailIgnoreCase("wendy-test@local.test");
    }

    // === requestPasswordReset()：合格帳號的成功路徑 ==============================

    @Test
    void requestPasswordReset_合格帳號建立Token並作廢舊Token_commit後才寄信() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        Account account = accountOf("hash");
        when(accounts.findByEmailIgnoreCase("wendy-test@local.test")).thenReturn(Optional.of(account));

        PasswordResetToken staleToken = new PasswordResetToken();
        when(passwordResetTokens.findByAccountIdAndUsedAtIsNullAndInvalidatedAtIsNull(ACCOUNT_ID))
                .thenReturn(List.of(staleToken));

        runWithSimulatedCommit(() -> service.requestPasswordReset("wendy-test@local.test", "1.2.3.4"));

        assertThat(staleToken.getInvalidatedAt()).isNotNull();

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokens).save(captor.capture());
        PasswordResetToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotBlank().hasSize(64); // SHA-256 hex
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES - 1));

        // afterCommit 已手動觸發，寄信任務應該已經被丟進 executor
        verify(mailTaskExecutor, times(1)).execute(any());
    }

    @Test
    void requestPasswordReset_沒有commit時不寄信() {
        rateLimiterIpAllows("1.2.3.4");
        rateLimiterEmailAllows();
        when(accounts.findByEmailIgnoreCase("wendy-test@local.test")).thenReturn(Optional.of(accountOf("hash")));

        // 故意不用 runWithSimulatedCommit，只初始化 synchronization 但不觸發 afterCommit
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.requestPasswordReset("wendy-test@local.test", "1.2.3.4");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(mailTaskExecutor, never()).execute(any());
    }

    // === resetPassword()：Token 驗證 ============================================
    //
    // 鎖序已改為 Account-first：先用不鎖的 scalar 查詢（findAccountIdByTokenHash）
    // 定位 accountId，再鎖 Account、再鎖 token，鎖內重新驗證一次所有狀態。刻意用
    // scalar Integer 而不是回傳整個 Entity——同一個 persistence context 內對同一
    // id 的重複查詢，Hibernate 會回傳同一個已受管理的 instance（identity map），
    // 如果第一次查詢就把 Entity 查出來，之後 findByTokenHashForUpdate 即使真的下了
    // SELECT ... FOR UPDATE，Java 端拿到的欄位值也未必是鎖定後的最新值。因此
    // 「定位」跟「鎖內複查」在這些測試裡永遠是兩個獨立的 stub，鎖內複查一律用
    // validToken()（或其他手動建構的狀態）單獨表達。

    @Test
    void resetPassword_定位時Token不存在丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);

        verify(accounts, never()).findByIdForUpdate(any());
        verify(passwordResetTokens, never()).findByTokenHashForUpdate(any());
    }

    @Test
    void resetPassword_定位後鎖內Token已被使用丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("old-hash")));
        PasswordResetToken lockedToken = validToken();
        lockedToken.setUsedAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(lockedToken));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_定位後鎖內Token已被作廢丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("old-hash")));
        PasswordResetToken lockedToken = validToken();
        lockedToken.setInvalidatedAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(lockedToken));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_定位後鎖內Token已過期丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("old-hash")));
        PasswordResetToken lockedToken = validToken();
        lockedToken.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(lockedToken));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_定位與鎖定後TokenAccountId不一致丟ResetTokenInvalid() {
        // 理論上不該發生，但明確擋一次：不能假設兩次查詢之間 token 所屬帳號沒有變動。
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("old-hash")));
        PasswordResetToken lockedToken = validToken();
        Account otherAccount = accountOf("other-hash");
        otherAccount.setId(ACCOUNT_ID + 1);
        lockedToken.setAccount(otherAccount);
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(lockedToken));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_對應帳號不存在丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);

        // Account 鎖失敗就必須直接中止，不該再去鎖 token
        verify(passwordResetTokens, never()).findByTokenHashForUpdate(any());
    }

    @Test
    void resetPassword_帳號已軟刪除丟ResetTokenInvalid() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        Account deleted = accountOf("hash");
        deleted.softDelete();
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(deleted));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(validToken()));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_帳號已變成OAuthOnly時防禦性擋下() {
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(accountOf(null)));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(validToken()));

        assertThatThrownBy(() -> service.resetPassword("raw-token", "Newpass@2026"))
                .isInstanceOf(ResetTokenInvalidException.class);
    }

    @Test
    void resetPassword_新密碼重用丟PasswordReused() {
        Account account = accountOf("old-hash");
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(validToken()));
        when(passwordEncoder.matches("SamePass@2026", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.resetPassword("raw-token", "SamePass@2026"))
                .isInstanceOf(PasswordReusedException.class);
    }

    @Test
    void resetPassword_鎖定順序為Account先於Token() {
        Account account = accountOf("old-hash");
        PasswordResetToken lockedToken = validToken();
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(lockedToken));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordResetTokens.findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(
                eq(ACCOUNT_ID), any())).thenReturn(List.of());

        service.resetPassword("raw-token", "Newpass@2026");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(accounts, passwordResetTokens);
        inOrder.verify(passwordResetTokens).findAccountIdByTokenHash(any());
        inOrder.verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        inOrder.verify(passwordResetTokens).findByTokenHashForUpdate(any());
    }

    // === resetPassword()：成功路徑 ===============================================

    @Test
    void resetPassword_成功時更新密碼遞增版本標記已使用_且不簽發JWT() {
        PasswordResetToken token = validToken();
        Account account = accountOf("old-hash");
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("Newpass@2026", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("Newpass@2026")).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordResetTokens.findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(ACCOUNT_ID, token.getId()))
                .thenReturn(List.of());

        service.resetPassword("raw-token", "Newpass@2026");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.getCredentialsVersion()).isEqualTo(3);
        assertThat(token.getUsedAt()).isNotNull();
        verify(jwtUtil, never()).generateToken(any(Account.class));
    }

    @Test
    void resetPassword_成功時作廢同帳號其他仍有效的Token_排除自己() {
        PasswordResetToken token = validToken();
        Account account = accountOf("old-hash");
        PasswordResetToken otherStillValid = new PasswordResetToken();
        when(passwordResetTokens.findAccountIdByTokenHash(any())).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(passwordResetTokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(passwordHistories.findTop4ByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordHistories.findByAccountIdOrderByCreatedAtDescIdDesc(ACCOUNT_ID)).thenReturn(List.of());
        when(passwordResetTokens.findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(ACCOUNT_ID, token.getId()))
                .thenReturn(List.of(otherStillValid));

        service.resetPassword("raw-token", "Newpass@2026");

        assertThat(otherStillValid.getInvalidatedAt()).isNotNull();
        verify(passwordResetTokens).findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(
                ACCOUNT_ID, token.getId());
    }

    private PasswordResetToken validToken() {
        Account account = accountOf("old-hash");
        PasswordResetToken token = new PasswordResetToken();
        token.setId(100);
        token.setAccount(account);
        token.setTokenHash("irrelevant-in-this-test");
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return token;
    }
}
