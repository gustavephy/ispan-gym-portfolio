package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthExchangeCode;
import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRegistrationTicketStatus;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.exception.OAuthRegistrationTicketInvalidException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 純 Mockito 單元測試：驗證 ticket 鎖定／驗證／消耗與 Account／Member／OauthAccount／
 * ExchangeCode 建立是不是真的照「全部成功才消耗」的順序寫——即使沒有真實 transaction，
 * 這裡至少能確認程式碼本身不會在建立資料之前就先把 ticket 標成 USED（真正的 rollback
 * 行為要靠真實 DB 才能驗證，見 memory/google_oauth_phase_scope.md 的測試範圍）。
 */
class GoogleOAuthRegistrationCreationServiceTest {

    private static final String TOKEN_HASH = "hash-of-registration-token";

    private final OAuthRegistrationTicketRepository tickets = mock(OAuthRegistrationTicketRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);
    private final OAuthExchangeCodeRepository exchangeCodes = mock(OAuthExchangeCodeRepository.class);
    private final OpaqueTokenGenerator tokenGenerator = mock(OpaqueTokenGenerator.class);
    private final GoogleOAuthProperties properties = mock(GoogleOAuthProperties.class);

    private final GoogleOAuthRegistrationCreationService service = new GoogleOAuthRegistrationCreationService(
            tickets, accounts, members, oauthAccounts, exchangeCodes, tokenGenerator, properties);

    @Test
    void create_Ticket不存在丟RegistrationTicketInvalid() {
        when(tickets.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(TOKEN_HASH, completeRequest()))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);

        verify(accounts, never()).save(any());
    }

    @Test
    void create_鎖定後Ticket已被使用丟RegistrationTicketInvalid() {
        OAuthRegistrationTicket ticket = pendingTicket();
        ticket.setStatus(OAuthRegistrationTicketStatus.USED);
        when(tickets.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.create(TOKEN_HASH, completeRequest()))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);

        verify(accounts, never()).save(any());
    }

    @Test
    void create_鎖定後Ticket已過期丟RegistrationTicketInvalid() {
        OAuthRegistrationTicket ticket = pendingTicket();
        ticket.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(tickets.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.create(TOKEN_HASH, completeRequest()))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);

        verify(accounts, never()).save(any());
    }

    /**
     * 對應必補案例「Registration 建立途中失敗 → ticket 仍為 PENDING」：模擬建立 OauthAccount
     * 階段失敗（例如唯一約束衝突），確認例外拋出前 ticket 的記憶體狀態沒有被動到——真實
     * transaction 下這代表 rollback 後 DB 裡的 ticket 仍是 PENDING，因為「標記 USED」
     * 這行本來就寫在所有 save() 之後，例外會讓它根本沒機會執行。
     */
    @Test
    void create_建立途中失敗_Ticket狀態未被異動仍為PENDING() {
        OAuthRegistrationTicket ticket = pendingTicket();
        when(tickets.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(ticket));
        when(oauthAccounts.save(any(OauthAccount.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.create(TOKEN_HASH, completeRequest()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(ticket.getStatus()).isEqualTo(OAuthRegistrationTicketStatus.PENDING);
        assertThat(ticket.getFinalizedAt()).isNull();
        verify(exchangeCodes, never()).save(any());
    }

    /**
     * 對應必補案例「Registration 成功 → Account/Member/OauthAccount/ExchangeCode 與
     * ticket USED 同時提交」：驗證成功路徑真的建立了全部四筆資料，且 ticket 最後才被
     * 標記 USED（順序見程式碼：所有 save()+flush() 都在標記 USED 之前）。
     */
    @Test
    void create_成功時建立全部資料並標記Ticket為Used() {
        OAuthRegistrationTicket ticket = pendingTicket();
        when(tickets.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(ticket));
        when(tokenGenerator.generate()).thenReturn("raw-exchange-code");
        when(tokenGenerator.hash("raw-exchange-code")).thenReturn("hash-of-exchange-code");
        when(properties.exchangeCodeTtl()).thenReturn(Duration.ofSeconds(90));

        String rawExchangeCode = service.create(TOKEN_HASH, completeRequest());

        assertThat(rawExchangeCode).isEqualTo("raw-exchange-code");
        assertThat(ticket.getStatus()).isEqualTo(OAuthRegistrationTicketStatus.USED);
        assertThat(ticket.getFinalizedAt()).isNotNull();

        org.mockito.ArgumentCaptor<Account> accountCaptor = org.mockito.ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getEmail()).isEqualTo("new-user@example.com");
        assertThat(savedAccount.getPasswordHash()).isNull();
        assertThat(savedAccount.getRoles()).contains(tw.com.ispan.gym.enums.account.AccountType.MEMBER);

        org.mockito.ArgumentCaptor<Member> memberCaptor = org.mockito.ArgumentCaptor.forClass(Member.class);
        verify(members).save(memberCaptor.capture());
        Member savedMember = memberCaptor.getValue();
        assertThat(savedMember.getFirstName()).isEqualTo("小明");
        assertThat(savedMember.getLastName()).isEqualTo("王");
        assertThat(savedMember.getPhone()).isEqualTo("0912345678");
        assertThat(savedMember.getStatus()).isEqualTo(tw.com.ispan.gym.enums.member.UserStatus.ACTIVE);
        assertThat(savedMember.getAccount()).isSameAs(savedAccount);

        org.mockito.ArgumentCaptor<OauthAccount> bindingCaptor = org.mockito.ArgumentCaptor.forClass(OauthAccount.class);
        verify(oauthAccounts).save(bindingCaptor.capture());
        OauthAccount savedBinding = bindingCaptor.getValue();
        assertThat(savedBinding.getOauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(savedBinding.getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(savedBinding.getProviderEmailVerified()).isTrue();
        assertThat(savedBinding.getAccount()).isSameAs(savedAccount);

        org.mockito.ArgumentCaptor<OAuthExchangeCode> codeCaptor = org.mockito.ArgumentCaptor.forClass(OAuthExchangeCode.class);
        verify(exchangeCodes).save(codeCaptor.capture());
        OAuthExchangeCode savedCode = codeCaptor.getValue();
        assertThat(savedCode.getCodeHash()).isEqualTo("hash-of-exchange-code");
        assertThat(savedCode.getAccount()).isSameAs(savedAccount);
    }

    private OAuthRegistrationTicket pendingTicket() {
        OAuthRegistrationTicket ticket = new OAuthRegistrationTicket();
        ticket.setId(1);
        ticket.setTokenHash(TOKEN_HASH);
        ticket.setOauthProvider(OAuthProvider.GOOGLE);
        ticket.setProviderUserId("google-sub-123");
        ticket.setProviderEmail("new-user@example.com");
        ticket.setProviderEmailVerified(true);
        ticket.setStatus(OAuthRegistrationTicketStatus.PENDING);
        ticket.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return ticket;
    }

    private OAuthRegistrationCompleteRequest completeRequest() {
        OAuthRegistrationCompleteRequest request = new OAuthRegistrationCompleteRequest();
        request.setRegistrationToken("raw-registration-token");
        request.setFirstName("小明");
        request.setLastName("王");
        request.setPhone("0912345678");
        request.setGender(Gender.M);
        return request;
    }
}
