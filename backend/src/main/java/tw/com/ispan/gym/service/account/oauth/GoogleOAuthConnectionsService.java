package tw.com.ispan.gym.service.account.oauth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.account.OAuthConnectionsResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;

/** 查詢目前登入 Account 的 Google 綁定狀態，供帳號設定頁顯示 */
@Service
public class GoogleOAuthConnectionsService {

    private final AccountRepository accounts;
    private final OauthAccountRepository oauthAccounts;

    public GoogleOAuthConnectionsService(AccountRepository accounts, OauthAccountRepository oauthAccounts) {
        this.accounts = accounts;
        this.oauthAccounts = oauthAccounts;
    }

    @Transactional(readOnly = true)
    public OAuthConnectionsResponse query(Integer accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        boolean hasPassword = account.getPasswordHash() != null;

        return oauthAccounts.findByAccountIdAndOauthProvider(accountId, OAuthProvider.GOOGLE)
                .map(binding -> new OAuthConnectionsResponse(
                        new OAuthConnectionsResponse.GoogleConnection(
                                true, binding.getProviderEmail(), binding.getLastLoginAt()),
                        hasPassword))
                .orElseGet(() -> OAuthConnectionsResponse.notLinked(hasPassword));
    }
}
