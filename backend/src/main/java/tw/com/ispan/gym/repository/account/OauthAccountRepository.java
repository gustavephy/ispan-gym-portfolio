package tw.com.ispan.gym.repository.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OauthAccount;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, Integer> {

    /** 以 (provider, sub) 查綁定並直接解出帳號；sub 只在同一 provider 內唯一 */
    @Query("""
            select a.account from OauthAccount a
                where a.oauthProvider = :provider and a.providerUserId = :subject
            """)
    Optional<Account> findAccountByProviderAndSubject(
            @Param("provider") OAuthProvider provider, @Param("subject") String subject);

    /**
     * 只取 accountId 的非鎖定版本——LOGIN 判定既有綁定時用這個先定位，再鎖 Account、
     * 鎖內重查一次綁定是否還在，避免直接把整個 Account entity 帶出鎖外使用（見
     * GoogleOAuthLoginService 的 Account-first 鎖序說明）。
     */
    @Query("""
            select a.account.id from OauthAccount a
                where a.oauthProvider = :provider and a.providerUserId = :subject
            """)
    Optional<Integer> findAccountIdByProviderAndSubject(
            @Param("provider") OAuthProvider provider, @Param("subject") String subject);

    /** 完整的綁定列——更新 providerEmail／lastLoginAt，或 LINK／UNLINK 需要操作綁定本身時用這個 */
    Optional<OauthAccount> findByOauthProviderAndProviderUserId(OAuthProvider provider, String subject);

    /** 一個 Account 最多綁一筆同 provider 的紀錄（UX_oauth_account_account_id_provider 保證） */
    Optional<OauthAccount> findByAccountIdAndOauthProvider(Integer accountId, OAuthProvider provider);
}
