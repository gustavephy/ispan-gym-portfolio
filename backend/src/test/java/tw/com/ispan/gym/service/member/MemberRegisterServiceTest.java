package tw.com.ispan.gym.service.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import tw.com.ispan.gym.dto.member.MemberRegisterRequest;
import tw.com.ispan.gym.dto.member.MemberRegisterResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.EmailVerification;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.account.VerificationPurpose;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.EmailVerificationRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.service.account.MailService;

/**
 * 純 Mockito 單元測試（沿用 CoachBookingServiceTest 的做法：專案無內嵌測試資料庫，
 * 不用 @SpringBootTest，直接 mock Repository/協作的 MailService）。
 *
 * 涵蓋：全新註冊／已存在帳號但無 Member 身分／SUSPENDED 硬性擋下／PENDING 重送與軟刪除
 * 重新啟用都不覆蓋密碼與個資／重寄冷卻與悲觀鎖／verifyEmail() 依 purpose 分流與各種失效情境。
 *
 * 併發鎖只驗證「有沒有呼叫 findByIdForUpdate 取鎖」，不模擬真正的多執行緒競爭——
 * 專案沒有內嵌測試資料庫可以驗證真實的資料庫悲觀鎖行為，這裡只能確認程式碼有沒有走到
 * 取鎖這一步，鎖本身是否真的serialize併發請求要靠實際資料庫驗證。
 */
class MemberRegisterServiceTest {

    private static final Integer ACCOUNT_ID = 1;
    private static final Integer MEMBER_ID = 10;
    private static final String EMAIL = "member@example.com";
    private static final long TTL_MINUTES = 10L;
    private static final long COOLDOWN_MINUTES = 1L;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmailVerificationRepository emailVerifications = mock(EmailVerificationRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final MailService mailService = mock(MailService.class);
    private final MemberRegisterService service = new MemberRegisterService(
            accounts, members, emailVerifications, passwordEncoder, mailService, TTL_MINUTES, COOLDOWN_MINUTES);

    // === 共用測試資料 ========================================================

    private MemberRegisterRequest request() {
        MemberRegisterRequest request = new MemberRegisterRequest();
        request.setFirstName("小明");
        request.setLastName("王");
        request.setEmail(EMAIL);
        request.setPhone("0912345678");
        request.setPassword("Passw0rd123");
        return request;
    }

    private Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
        account.setPasswordHash("old-hashed-password");
        account.getRoles().add(AccountType.MEMBER);
        return account;
    }

    private Member member(Account account, UserStatus status) {
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setAccount(account);
        member.setFirstName("舊名字");
        member.setLastName("舊姓氏");
        member.setPhone("0900000000");
        member.setStatus(status);
        return member;
    }

    private EmailVerification verification(Account account, VerificationPurpose purpose, LocalDateTime expiresAt) {
        EmailVerification verification = new EmailVerification();
        verification.setAccount(account);
        verification.setTargetRole(AccountType.MEMBER);
        verification.setPurpose(purpose);
        verification.setToken(UUID.randomUUID());
        verification.setExpiresAt(expiresAt);
        return verification;
    }

    private void stubAccountSaveAssignsId() {
        when(accounts.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(ACCOUNT_ID);
            }
            return a;
        });
    }

    private void stubNoRecentVerification() {
        when(emailVerifications.findTopByAccountIdAndTargetRoleAndVerifiedAtIsNullOrderByCreatedAtDesc(
                eq(ACCOUNT_ID), eq(AccountType.MEMBER))).thenReturn(Optional.empty());
    }

    private void stubRecentVerificationWithinCooldown() {
        EmailVerification recent = verification(account(), VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(TTL_MINUTES));
        // 20 秒前才發過，遠小於 1 分鐘冷卻
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(20));
        when(emailVerifications.findTopByAccountIdAndTargetRoleAndVerifiedAtIsNullOrderByCreatedAtDesc(
                eq(ACCOUNT_ID), eq(AccountType.MEMBER))).thenReturn(Optional.of(recent));
    }

    // === register()：全新註冊 ================================================

    @Test
    void 全新註冊成功建立帳號與會員並發REGISTRATION驗證信() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Passw0rd123")).thenReturn("new-hashed-password");
        stubAccountSaveAssignsId();
        when(members.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        MemberRegisterResponse response = service.register(request());

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.message()).isNotBlank();
        verify(accounts).save(argThat(a -> a.getPasswordHash().equals("new-hashed-password")
                && a.getRoles().contains(AccountType.MEMBER)));
        verify(members).save(argThat(m -> m.getStatus() == UserStatus.PENDING
                && m.getFirstName().equals("小明")));
        verify(mailService).sendVerificationEmail(eq(EMAIL), any(UUID.class), eq(TTL_MINUTES),
                eq(VerificationPurpose.REGISTRATION));
    }

    // === register()：已存在帳號的各種分支 =====================================

    @Test
    void 已有帳號但無Member身分回409且不透露具體身分不做任何寫入() {
        Account account = account();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("此 Email 已有帳號，請改用登入。");

        verify(passwordEncoder, never()).encode(anyString());
        verify(emailVerifications, never()).save(any());
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    @Test
    void SUSPENDED會員重新註冊回409不論是否軟刪除都擋下() {
        Account account = account();
        Member member = member(account, UserStatus.SUSPENDED);
        member.softDelete(); // 即使同時被軟刪除，SUSPENDED 仍是最優先的硬性關卡
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessRuleException.class);

        verify(emailVerifications, never()).save(any());
        verify(accounts, never()).findByIdForUpdate(any());
    }

    @Test
    void ACTIVE會員重複註冊回409() {
        Account account = account();
        Member member = member(account, UserStatus.ACTIVE);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("此 Email 已被註冊");

        verify(emailVerifications, never()).save(any());
    }

    @Test
    void PENDING重送只重寄驗證信不覆蓋密碼與個資也不洩漏真實資料() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubNoRecentVerification();

        MemberRegisterResponse response = service.register(request());

        // 回應只有 email／訊息，型別上就不可能帶出舊會員的姓名／電話／id
        assertThat(response.email()).isEqualTo(EMAIL);
        // 密碼與個人資料完全沒被動過
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(member.getFirstName()).isEqualTo("舊名字");
        assertThat(account.getPasswordHash()).isEqualTo("old-hashed-password");
        // 舊 token 作廢、發新 REGISTRATION token
        verify(emailVerifications).deleteByAccountIdAndTargetRoleAndVerifiedAtIsNull(ACCOUNT_ID, AccountType.MEMBER);
        verify(mailService).sendVerificationEmail(eq(EMAIL), any(UUID.class), eq(TTL_MINUTES),
                eq(VerificationPurpose.REGISTRATION));
    }

    @Test
    void 軟刪除帳號重新送出表單只發REACTIVATION信不會直接復活() {
        Account account = account();
        account.softDelete();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubNoRecentVerification();

        service.register(request());

        // 请求当下不复活，要等真的点验证信才行——deletedAt 依然保持原样
        assertThat(account.getDeletedAt()).isNotNull();
        verify(passwordEncoder, never()).encode(anyString());
        verify(mailService).sendVerificationEmail(eq(EMAIL), any(UUID.class), eq(TTL_MINUTES),
                eq(VerificationPurpose.REACTIVATION));
    }

    @Test
    void PENDING重送在冷卻時間內回409且不發信() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubRecentVerificationWithinCooldown();

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessRuleException.class);

        // 有取鎖檢查冷卻，但確認沒有真的發新信
        verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        verify(emailVerifications, never()).deleteByAccountIdAndTargetRoleAndVerifiedAtIsNull(any(), any());
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    @Test
    void 重新啟用在冷卻時間內回409且不發信() {
        Account account = account();
        account.softDelete();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubRecentVerificationWithinCooldown();

        assertThatThrownBy(() -> service.register(request()))
                .isInstanceOf(BusinessRuleException.class);

        verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    // === isEmailAvailableForRegistration()：即時可用性檢查 =======================
    // 刻意跟上面 register() 的每個分支一一對應，確認「可用」與「register() 實際會不會成功」一致

    @Test
    void 可用性檢查_全新email視為可用() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isTrue();
    }

    @Test
    void 可用性檢查_已有帳號但無Member身分視為不可用() {
        Account account = account();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isFalse();
    }

    @Test
    void 可用性檢查_SUSPENDED視為不可用() {
        Account account = account();
        Member member = member(account, UserStatus.SUSPENDED);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isFalse();
    }

    @Test
    void 可用性檢查_ACTIVE視為不可用() {
        Account account = account();
        Member member = member(account, UserStatus.ACTIVE);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isFalse();
    }

    @Test
    void 可用性檢查_PENDING視為可用因為送出後只會重寄驗證信不會報錯() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isTrue();
    }

    @Test
    void 可用性檢查_軟刪除帳號視為可用因為送出後只會觸發重新啟用信不會報錯() {
        Account account = account();
        account.softDelete();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThat(service.isEmailAvailableForRegistration(EMAIL)).isTrue();
    }

    @Test
    void 可用性檢查_只讀不觸發任何寫入或寄信() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.isEmailAvailableForRegistration(EMAIL);

        verify(accounts, never()).save(any());
        verify(accounts, never()).findByIdForUpdate(any());
        verify(emailVerifications, never()).save(any());
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    // === resendVerification()：匿名重寄，一律靜默處理 ===========================

    @Test
    void 重寄_email不存在靜默不做事() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.resendVerification(EMAIL);

        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    @Test
    void 重寄_無Member身分靜默不做事() {
        Account account = account();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        service.resendVerification(EMAIL);

        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
        verify(accounts, never()).findByIdForUpdate(any());
    }

    @Test
    void 重寄_SUSPENDED靜默不做事() {
        Account account = account();
        Member member = member(account, UserStatus.SUSPENDED);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        service.resendVerification(EMAIL);

        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    @Test
    void 重寄_ACTIVE沒有東西可重寄靜默不做事() {
        Account account = account();
        Member member = member(account, UserStatus.ACTIVE);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        service.resendVerification(EMAIL);

        verify(accounts, never()).findByIdForUpdate(any());
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    @Test
    void 重寄_PENDING成功重寄REGISTRATION信() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubNoRecentVerification();

        service.resendVerification(EMAIL);

        verify(mailService).sendVerificationEmail(eq(EMAIL), any(UUID.class), eq(TTL_MINUTES),
                eq(VerificationPurpose.REGISTRATION));
    }

    @Test
    void 重寄_軟刪除帳號成功重寄REACTIVATION信() {
        Account account = account();
        account.softDelete();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubNoRecentVerification();

        service.resendVerification(EMAIL);

        verify(mailService).sendVerificationEmail(eq(EMAIL), any(UUID.class), eq(TTL_MINUTES),
                eq(VerificationPurpose.REACTIVATION));
    }

    @Test
    void 重寄_冷卻時間內靜默跳過但確實有取鎖() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        stubRecentVerificationWithinCooldown();

        service.resendVerification(EMAIL);

        // 併發防護：確實有取鎖，只是查完冷卻後判斷還沒到時間，靜默跳過不發信
        verify(accounts).findByIdForUpdate(ACCOUNT_ID);
        verify(mailService, never()).sendVerificationEmail(any(), any(), anyLong(), any());
    }

    // === verifyEmail() ======================================================

    @Test
    void verifyEmail_REGISTRATION成功轉ACTIVE並回傳purpose() {
        Account account = account();
        Member member = member(account, UserStatus.PENDING);
        EmailVerification verification = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        VerificationPurpose result = service.verifyEmail(verification.getToken());

        assertThat(result).isEqualTo(VerificationPurpose.REGISTRATION);
        assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(verification.getVerifiedAt()).isNotNull();
    }

    @Test
    void verifyEmail_REACTIVATION成功復活帳號與會員並補回角色() {
        Account account = account();
        account.softDelete();
        account.getRoles().clear(); // 模擬角色曾經缺失的情境，驗證無條件補回的修正
        Member member = member(account, UserStatus.PENDING);
        member.softDelete();
        EmailVerification verification = verification(account, VerificationPurpose.REACTIVATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        VerificationPurpose result = service.verifyEmail(verification.getToken());

        assertThat(result).isEqualTo(VerificationPurpose.REACTIVATION);
        assertThat(account.getDeletedAt()).isNull();
        assertThat(member.getDeletedAt()).isNull();
        assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account.getRoles()).contains(AccountType.MEMBER);
    }

    @Test
    void verifyEmail_只有Member被刪Account未被刪也會無條件補回MEMBER角色() {
        Account account = account(); // account.deletedAt 保持 null
        account.getRoles().clear();
        Member member = member(account, UserStatus.PENDING);
        member.softDelete(); // 只有 member 被刪
        EmailVerification verification = verification(account, VerificationPurpose.REACTIVATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        service.verifyEmail(verification.getToken());

        assertThat(account.getRoles()).contains(AccountType.MEMBER);
        assertThat(member.getDeletedAt()).isNull();
    }

    @Test
    void verifyEmail_token不存在回404() {
        UUID token = UUID.randomUUID();
        when(emailVerifications.findByToken(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail(token))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void verifyEmail_targetRole非MEMBER拒絕() {
        Account account = account();
        EmailVerification verification = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(5));
        verification.setTargetRole(AccountType.EMPLOYEE);
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void verifyEmail_purpose為null視為失效不噴NPE() {
        Account account = account();
        EmailVerification verification = verification(account, null, LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void verifyEmail_已驗證過的舊資料purpose為null時重複點擊也不噴NPE() {
        Account account = account();
        EmailVerification verification = verification(account, null, LocalDateTime.now().plusMinutes(5));
        verification.setVerifiedAt(LocalDateTime.now().minusDays(1)); // 遷移前就已經驗證過
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void verifyEmail_重複點擊視為成功不重新處理() {
        Account account = account();
        Member member = member(account, UserStatus.ACTIVE);
        EmailVerification verification = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(5));
        verification.setVerifiedAt(LocalDateTime.now().minusMinutes(1));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));

        VerificationPurpose result = service.verifyEmail(verification.getToken());

        assertThat(result).isEqualTo(VerificationPurpose.REGISTRATION);
        verify(members, never()).findByAccountId(any());
    }

    @Test
    void verifyEmail_過期連結拒絕() {
        Account account = account();
        EmailVerification verification = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().minusMinutes(1));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("過期");
    }

    @Test
    void verifyEmail_REGISTRATIONpurpose但member已非PENDING拒絕() {
        Account account = account();
        Member member = member(account, UserStatus.ACTIVE); // 已經是 ACTIVE 了
        EmailVerification verification = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(verification.getVerifiedAt()).isNull();
    }

    @Test
    void verifyEmail_REACTIVATIONpurpose但其實沒有被刪除拒絕() {
        Account account = account(); // 沒有軟刪除
        Member member = member(account, UserStatus.PENDING); // 也沒有軟刪除
        EmailVerification verification = verification(account, VerificationPurpose.REACTIVATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(verification.getToken())).thenReturn(Optional.of(verification));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.verifyEmail(verification.getToken()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void verifyEmail_SUSPENDED會員不論REGISTRATION或REACTIVATION都拒絕() {
        Account account = account();
        Member member = member(account, UserStatus.SUSPENDED);
        EmailVerification registrationToken = verification(account, VerificationPurpose.REGISTRATION,
                LocalDateTime.now().plusMinutes(5));
        EmailVerification reactivationToken = verification(account, VerificationPurpose.REACTIVATION,
                LocalDateTime.now().plusMinutes(5));
        when(emailVerifications.findByToken(registrationToken.getToken())).thenReturn(Optional.of(registrationToken));
        when(emailVerifications.findByToken(reactivationToken.getToken())).thenReturn(Optional.of(reactivationToken));
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.verifyEmail(registrationToken.getToken()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.verifyEmail(reactivationToken.getToken()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
