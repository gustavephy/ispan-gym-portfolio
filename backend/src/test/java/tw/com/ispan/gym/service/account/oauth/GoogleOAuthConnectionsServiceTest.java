package tw.com.ispan.gym.service.account.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.account.OAuthConnectionsResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;

class GoogleOAuthConnectionsServiceTest {

    private static final Integer ACCOUNT_ID = 30;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OauthAccountRepository oauthAccounts = mock(OauthAccountRepository.class);

    private final GoogleOAuthConnectionsService service = new GoogleOAuthConnectionsService(accounts, oauthAccounts);

    @Test
    void query_Account不存在_丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.query(ACCOUNT_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void query_未綁定時回傳NotLinked_仍帶HasPassword() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf("hash")));
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.empty());

        OAuthConnectionsResponse response = service.query(ACCOUNT_ID);

        assertThat(response.google().linked()).isFalse();
        assertThat(response.google().email()).isNull();
        assertThat(response.hasPassword()).isTrue();
    }

    @Test
    void query_已綁定時回傳綁定Email與時間() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountOf(null)));
        OauthAccount binding = new OauthAccount();
        binding.setProviderEmail("user@example.com");
        LocalDateTime linkedAt = LocalDateTime.now();
        binding.setLastLoginAt(linkedAt);
        when(oauthAccounts.findByAccountIdAndOauthProvider(ACCOUNT_ID, OAuthProvider.GOOGLE))
                .thenReturn(Optional.of(binding));

        OAuthConnectionsResponse response = service.query(ACCOUNT_ID);

        assertThat(response.google().linked()).isTrue();
        assertThat(response.google().email()).isEqualTo("user@example.com");
        assertThat(response.google().linkedAt()).isEqualTo(linkedAt);
        // OAuth-only account——不能拿去解除唯一登入方式
        assertThat(response.hasPassword()).isFalse();
    }

    private Account accountOf(String passwordHash) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setPasswordHash(passwordHash);
        return account;
    }
}
