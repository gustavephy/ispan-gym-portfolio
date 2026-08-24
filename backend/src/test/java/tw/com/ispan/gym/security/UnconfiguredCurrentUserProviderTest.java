package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 純 Mockito 單元測試（專案無內嵌測試資料庫，不用 @SpringBootTest）。
 *
 * 這支是正式環境（{@code @Profile("!dev")}）唯一的身分來源，全專案 controller 都靠它
 * 決定「你是誰」，但 dev profile 下根本不會被載入，本機開發完全踩不到——
 * 因此逐一驗證各種登入方式的解析，以及帳號／會員／員工三層的停用與軟刪除關卡。
 *
 * SecurityContextHolder 是靜態狀態，每則測試後清空，避免污染其他測試。
 *
 * member_id／employee_id 與 account_id 已脫鉤，測資刻意用不同號碼，
 * 確保回傳的是對應身分表的 id 而不是順手回了 account_id。
 */
class UnconfiguredCurrentUserProviderTest {

    private static final Integer ACCOUNT_ID = 7;
    private static final Integer MEMBER_ID = 70;   // 與 account 刻意不同號
    private static final Integer EMPLOYEE_ID = 71; // 與 account 刻意不同號
    private static final String EMAIL = "user@example.com";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);

    private final UnconfiguredCurrentUserProvider provider =
            new UnconfiguredCurrentUserProvider(accounts, members, employees, oauthAccounts);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // === 共用測試資料 ========================================================

    private Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
        return account;
    }

    private Member member(UserStatus status) {
        Member member = new Member();
        member.setId(MEMBER_ID);
        member.setStatus(status);
        return member;
    }

    private Employee employee(EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setStatus(status);
        return employee;
    }

    private void authenticateAs(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** 一般帳密登入：principal 是 UserDetails，username 即 email */
    private void loginWithUserDetails() {
        authenticateAs(new UsernamePasswordAuthenticationToken(
                User.withUsername(EMAIL).password("x").authorities("ROLE_MEMBER").build(),
                null, AuthorityUtils.createAuthorityList("ROLE_MEMBER")));
    }

    /** OAuth 登入：principal 是 OAuth2User，可帶 sub／email 屬性 */
    private void loginWithOAuth(String subject, String email) {
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("sub", subject);
        if (email != null) {
            attributes.put("email", email);
        }
        // DefaultOAuth2User 要求 nameAttributeKey 對應的屬性必須存在
        String nameKey = subject != null ? "sub" : "email";
        OAuth2User oauth2User = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, nameKey);
        authenticateAs(new OAuth2AuthenticationToken(
                oauth2User, AuthorityUtils.createAuthorityList("ROLE_USER"), "google"));
    }

    private void stubAccountByEmail() {
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(account()));
    }

    // === requireAuthentication()：登入態把關 ===================================

    @Test
    void 完全沒有登入資訊時要求先登入() {
        assertThatThrownBy(() -> provider.requireCurrentUserId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("請先登入");
    }

    @Test
    void 匿名使用者視為未登入() {
        // Spring Security 對未登入請求會塞一個 anonymousUser，不能當成已登入
        authenticateAs(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> provider.requireCurrentUserId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verify(accounts, never()).findByEmailIgnoreCase(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 尚未通過驗證的token視為未登入() {
        TestingAuthenticationToken notAuthenticated = new TestingAuthenticationToken(EMAIL, null);
        notAuthenticated.setAuthenticated(false);
        authenticateAs(notAuthenticated);

        assertThatThrownBy(() -> provider.requireCurrentUserId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    // === resolveAccount()：各種登入方式 ========================================

    @Test
    void 帳密登入以UserDetails的username查帳號() {
        loginWithUserDetails();
        stubAccountByEmail();

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
        verify(accounts).findByEmailIgnoreCase(EMAIL);
    }

    @Test
    void OAuth登入優先以provider與sub查綁定表() {
        loginWithOAuth("google-sub-123", EMAIL);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(account()));

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
        // 綁定命中就不必再用 email 查（email 可能被使用者在 Google 端改掉）
        verify(accounts, never()).findByEmailIgnoreCase(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void OAuth綁定查不到時退回用email查() {
        loginWithOAuth("google-sub-123", EMAIL);
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        stubAccountByEmail();

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
        verify(accounts).findByEmailIgnoreCase(EMAIL);
    }

    @Test
    void OAuth沒有sub屬性時退回用email查() {
        loginWithOAuth(null, EMAIL);
        stubAccountByEmail();

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
        verify(oauthAccounts, never()).findAccountByProviderAndSubject(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 無法辨識的OAuthprovider不會拋錯而是退回email() {
        Map<String, Object> attributes = Map.of("sub", "sub-1", "email", EMAIL);
        OAuth2User oauth2User = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "sub");
        // registrationId 不在 OAuthProvider enum 內（例如日後新增的 facebook 尚未建 enum）
        authenticateAs(new OAuth2AuthenticationToken(
                oauth2User, AuthorityUtils.createAuthorityList("ROLE_USER"), "facebook"));
        stubAccountByEmail();

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void 查無對應帳號時要求重新登入() {
        loginWithUserDetails();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.requireCurrentUserId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("找不到登入資訊對應的帳號");
    }

    @Test
    void 帳號已軟刪除時拒絕() {
        loginWithUserDetails();
        Account deleted = account();
        deleted.softDelete();
        when(accounts.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> provider.requireCurrentUserId())
                .isInstanceOf(DisabledException.class)
                .hasMessageContaining("此帳號已停用");
    }

    // === requireCurrentMemberId() =============================================

    @Test
    void 取會員id回傳的是member_id不是account_id() {
        loginWithUserDetails();
        stubAccountByEmail();
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.ACTIVE)));

        // 兩者已脫鉤，回錯就會把別人的資料當成自己的
        assertThat(provider.requireCurrentMemberId()).isEqualTo(MEMBER_ID);
        assertThat(provider.requireCurrentMemberId()).isNotEqualTo(ACCOUNT_ID);
    }

    @Test
    void 帳號沒有會員身分時拒絕() {
        loginWithUserDetails();
        stubAccountByEmail();
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.requireCurrentMemberId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("會員帳號");
    }

    @Test
    void 停權或待驗證的會員不可使用會員功能() {
        loginWithUserDetails();
        stubAccountByEmail();

        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.SUSPENDED)));
        assertThatThrownBy(() -> provider.requireCurrentMemberId())
                .isInstanceOf(DisabledException.class);

        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.PENDING)));
        assertThatThrownBy(() -> provider.requireCurrentMemberId())
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void 已軟刪除的會員不可使用會員功能() {
        loginWithUserDetails();
        stubAccountByEmail();
        Member deleted = member(UserStatus.ACTIVE); // 狀態仍是 ACTIVE，只有 deletedAt 有值
        deleted.setDeletedAt(LocalDateTime.now());
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> provider.requireCurrentMemberId())
                .isInstanceOf(DisabledException.class);
    }

    // === requireCurrentEmployeeId() ===========================================

    @Test
    void 取員工id回傳的是employee_id不是account_id() {
        loginWithUserDetails();
        stubAccountByEmail();
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

        assertThat(provider.requireCurrentEmployeeId()).isEqualTo(EMPLOYEE_ID);
        assertThat(provider.requireCurrentEmployeeId()).isNotEqualTo(ACCOUNT_ID);
    }

    @Test
    void 帳號沒有員工身分時拒絕() {
        loginWithUserDetails();
        stubAccountByEmail();
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.requireCurrentEmployeeId())
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessageContaining("員工帳號");
    }

    @Test
    void 待審核或已離職的員工不可使用員工功能() {
        loginWithUserDetails();
        stubAccountByEmail();

        // 申請尚未通過審核就想操作員工端功能
        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EmployeeStatus.PENDING_APPROVAL)));
        assertThatThrownBy(() -> provider.requireCurrentEmployeeId())
                .isInstanceOf(DisabledException.class);

        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EmployeeStatus.INACTIVE)));
        assertThatThrownBy(() -> provider.requireCurrentEmployeeId())
                .isInstanceOf(DisabledException.class);

        // 被拒絕的申請同樣不算在職
        when(employees.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(employee(EmployeeStatus.REJECTED)));
        assertThatThrownBy(() -> provider.requireCurrentEmployeeId())
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void 已軟刪除的員工不可使用員工功能() {
        loginWithUserDetails();
        stubAccountByEmail();
        Employee deleted = employee(EmployeeStatus.ACTIVE);
        deleted.setDeletedAt(LocalDateTime.now());
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> provider.requireCurrentEmployeeId())
                .isInstanceOf(DisabledException.class);
    }

    // === 同一帳號兼具兩種身分 ==================================================

    @Test
    void 同一帳號可同時取得會員與員工id() {
        // Account 架構的核心：員工也能以會員身分消費，共用同一組登入憑證
        loginWithUserDetails();
        stubAccountByEmail();
        when(members.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(member(UserStatus.ACTIVE)));
        when(employees.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

        assertThat(provider.requireCurrentUserId()).isEqualTo(ACCOUNT_ID);
        assertThat(provider.requireCurrentMemberId()).isEqualTo(MEMBER_ID);
        assertThat(provider.requireCurrentEmployeeId()).isEqualTo(EMPLOYEE_ID);
    }
}
