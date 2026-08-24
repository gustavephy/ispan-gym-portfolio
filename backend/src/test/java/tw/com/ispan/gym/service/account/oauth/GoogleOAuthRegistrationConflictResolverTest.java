package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.exception.OAuthAccountLinkRequiredException;
import tw.com.ispan.gym.exception.OAuthRegistrationTicketInvalidException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;

/**
 * 純 Mockito 單元測試：驗證 {@link GoogleOAuthRegistrationCreationService#create} 因唯一
 * 約束衝突失敗後，這裡怎麼把「未知的完整性衝突」重新判讀成兩種已知原因之一——對應必補案例
 * 「唯一約束衝突 → creation rollback 後 conflict resolver 正確分類」的分類半段。
 */
class GoogleOAuthRegistrationConflictResolverTest {

    private static final String TOKEN_HASH = "hash-of-registration-token";

    private final OAuthRegistrationTicketRepository tickets = mock(OAuthRegistrationTicketRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);

    private final GoogleOAuthRegistrationConflictResolver resolver =
            new GoogleOAuthRegistrationConflictResolver(tickets, accounts, oauthAccounts);

    @Test
    void resolve_Ticket重讀不到丟RegistrationTicketInvalid() {
        when(tickets.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TOKEN_HASH))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);
    }

    @Test
    void resolve_GoogleSub已被併發的另一次註冊綁定_丟RegistrationTicketInvalid() {
        OAuthRegistrationTicket ticket = ticketOf();
        when(tickets.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(ticket));
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(new Account()));

        assertThatThrownBy(() -> resolver.resolve(TOKEN_HASH))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);
    }

    @Test
    void resolve_Email已被併發的另一個流程註冊_丟AccountLinkRequired() {
        OAuthRegistrationTicket ticket = ticketOf();
        when(tickets.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(ticket));
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("new-user@example.com")).thenReturn(Optional.of(new Account()));

        assertThatThrownBy(() -> resolver.resolve(TOKEN_HASH))
                .isInstanceOf(OAuthAccountLinkRequiredException.class);
    }

    @Test
    void resolve_兩種已知原因都排除時丟未知的RegistrationTicketInvalid() {
        OAuthRegistrationTicket ticket = ticketOf();
        when(tickets.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(ticket));
        when(oauthAccounts.findAccountByProviderAndSubject(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.empty());
        when(accounts.findByEmailIgnoreCase("new-user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TOKEN_HASH))
                .isInstanceOf(OAuthRegistrationTicketInvalidException.class);
    }

    private OAuthRegistrationTicket ticketOf() {
        OAuthRegistrationTicket ticket = new OAuthRegistrationTicket();
        ticket.setId(1);
        ticket.setTokenHash(TOKEN_HASH);
        ticket.setOauthProvider(OAuthProvider.GOOGLE);
        ticket.setProviderUserId("google-sub-123");
        ticket.setProviderEmail("new-user@example.com");
        ticket.setProviderEmailVerified(true);
        ticket.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return ticket;
    }
}
