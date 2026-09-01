package tw.com.ispan.gym.service.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import tw.com.ispan.gym.dto.account.AccountMeResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.security.AccountAuthorityResolver;

/**
 * 帳號層級的自助操作，不屬於密碼生命週期（{@link PasswordService}）也不屬於
 * OAuth 綁定（{@code oauth} 子套件）：查詢身份快照、全裝置登出。
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AccountAuthorityResolver authorityResolver;
    private final EntityManager entityManager;

    public AccountService(AccountRepository accounts, AccountAuthorityResolver authorityResolver,
            EntityManager entityManager) {
        this.accounts = accounts;
        this.authorityResolver = authorityResolver;
        this.entityManager = entityManager;
    }

    /**
     * roles 每次都重新用 {@link AccountAuthorityResolver} 現查，不是登入當下存好的快照——
     * 帳號被停權／復權、身分被調整後，這裡回傳的一定是呼叫當下的即時結果，跟
     * {@code LoginResponse}／JWT 裡可能已經過期的 roles 不同，也是這支端點存在的意義。
     */
    @Transactional(readOnly = true)
    public AccountMeResponse getMe(Integer accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        return new AccountMeResponse(account.getId(), account.getEmail(),
                authorityResolver.resolveRoleNames(account), account.getCreatedAt());
    }

    /**
     * 遞增 credentialsVersion，讓包含這次請求所用 token 在內的所有既有 JWT 立即失效。
     * 跟修改密碼故意保留目前裝置登入不同——全裝置登出的語意就是連呼叫者自己也一併踢掉，
     * 不核發新 token；前端收到成功後應自行清掉本機登入狀態並導去登入頁。
     */
    @Transactional
    public void logoutAllDevices(Integer accountId) {
        Account locked = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("帳號不存在"));
        // OSIV + JwtAuthFilter 的預載會讓 locked 是鎖前的舊實例，getCredentialsVersion() 讀到
        // 舊值。沒有這行 refresh，logoutAllDevices 與併發的 change/set-password 交錯時，兩邊都
        // 讀到同一個舊版本、各自 +1，最終只遞增一次——可能漏掉對那次剛簽發的 JWT 的失效。
        entityManager.refresh(locked);
        locked.setCredentialsVersion(locked.getCredentialsVersion() + 1);
    }
}
