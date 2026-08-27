package tw.com.ispan.gym.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.account.AccountMeResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;

class AccountServiceTest {

    private static final Integer ACCOUNT_ID = 30;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountAuthorityResolver authorityResolver = mock(AccountAuthorityResolver.class);

    private final AccountService service = new AccountService(accounts, authorityResolver);

    // === getMe ================================================================

    @Test
    void getMe_Account不存在_丟ResourceNotFound() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(ACCOUNT_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * roles 必須每次都現查，不能是存在 Account 上的快取欄位——模擬帳號在登入後
     * 被停權的情境，確認 getMe 回傳的是查詢當下的結果，不是登入時的舊快照。
     */
    @Test
    void getMe_roles每次重新查詢_不是登入當下的舊快照() {
        Account account = accountOf();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(100);
        account.setCreatedAt(createdAt);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of());

        AccountMeResponse response = service.getMe(ACCOUNT_ID);

        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.email()).isEqualTo("wendy@example.com");
        assertThat(response.roles()).isEmpty();
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void getMe_roles反映AccountAuthorityResolver的即時結果() {
        Account account = accountOf();
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(authorityResolver.resolveRoleNames(account)).thenReturn(List.of("MEMBER", "EMPLOYEE", "ADMIN"));

        AccountMeResponse response = service.getMe(ACCOUNT_ID);

        assertThat(response.roles()).containsExactly("MEMBER", "EMPLOYEE", "ADMIN");
    }

    // === logoutAllDevices ======================================================

    @Test
    void logoutAllDevices_Account不存在_丟ResourceNotFound() {
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.logoutAllDevices(ACCOUNT_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void logoutAllDevices_遞增credentialsVersion_用悲觀鎖查詢() {
        Account account = accountOf();
        account.setCredentialsVersion(3);
        when(accounts.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));

        service.logoutAllDevices(ACCOUNT_ID);

        assertThat(account.getCredentialsVersion()).isEqualTo(4);
    }

    private Account accountOf() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setEmail("wendy@example.com");
        return account;
    }
}
