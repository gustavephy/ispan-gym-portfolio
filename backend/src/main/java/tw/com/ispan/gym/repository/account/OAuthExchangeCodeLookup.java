package tw.com.ispan.gym.repository.account;

/**
 * 兌換 Exchange Code 前的非鎖定純量投影——只取 {@code accountId}／核發當下的
 * {@code issuedCredentialsVersion}／{@code returnPath}，不取整個 entity。
 *
 * <p>{@code account_id} 在這張表上是 {@code NOT NULL}（每個 Code 一定對應一個已存在的
 * Account），跟 {@link OAuthTransactionView} 的 LOGIN 情境不同，不需要 LEFT JOIN 也不會
 * 排除任何列。真正要鎖定的 Account 由呼叫端拿著這裡的 {@code accountId} 另外用
 * {@code AccountRepository.findByIdForUpdate} 取得，這個投影本身不持有、也不該持有
 * Account entity。
 */
public record OAuthExchangeCodeLookup(Integer accountId, int issuedCredentialsVersion, String returnPath) {
}
