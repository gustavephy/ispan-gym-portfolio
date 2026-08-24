package tw.com.ispan.gym.repository.account;

import java.time.LocalDateTime;

import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;

/**
 * OAuth callback／resolver 需要的 transaction 欄位投影。
 *
 * <p>刻意不回傳 {@code OAuthTransaction} entity：這兩個呼叫點（
 * {@link tw.com.ispan.gym.security.oauth.GoogleOAuthSuccessHandler} 與
 * {@link tw.com.ispan.gym.security.oauth.GoogleOAuthAuthorizationRequestResolver}）
 * 都不在 {@code @Transactional} 方法內，repository 自帶的短交易一結束 entity 就
 * detached，之後存取 LAZY 關聯會丟 {@code LazyInitializationException}。用投影
 * 直接取出 {@code accountId} 純量值，就不需要為了遷就 handler 的交易邊界把
 * {@code account} 關聯改成 EAGER——那會讓每一次 transaction 查詢都多載入一個
 * Account，包括 resolver 只是驗證 state、根本用不到 Account 的情況。
 *
 * <p>{@code accountId} 在 LOGIN 用途為 null，LINK 用途必為非 null。
 */
public record OAuthTransactionView(
        String stateHash,
        OAuthProvider oauthProvider,
        OAuthTransactionPurpose purpose,
        OAuthTransactionStatus status,
        Integer accountId,
        String returnPath,
        LocalDateTime expiresAt) {
}
