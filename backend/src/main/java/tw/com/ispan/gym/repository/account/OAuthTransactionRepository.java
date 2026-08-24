package tw.com.ispan.gym.repository.account;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.OAuthTransaction;

/**
 * 所有 {@code @Modifying} 方法直接在介面上標 {@code @Transactional}，不依賴呼叫端
 * 提供交易——這裡的呼叫端（Success/Failure Handler、Registration Coordinator）刻意
 * 不是 {@code @Transactional}（Handler 不在代理呼叫鏈上很難疊加、Coordinator 需要
 * 讓 flush-then-catch 的併發防護維持在各自獨立的 transaction 邊界，見
 * GoogleOAuthRegistrationService 的類別註解），跟 {@code PasswordResetTokenRepository}
 * 那種「交易開在呼叫端排程方法」的既有慣例不同，是刻意的、有理由的偏離，不是疏漏。
 */
public interface OAuthTransactionRepository extends JpaRepository<OAuthTransaction, Integer> {

    /**
     * 唯讀投影查詢，用於 Resolver 發起前的驗證與 Callback 開始時讀取 purpose／
     * accountId／returnPath——這些欄位建立後不會再變動，之後即使被別的 request 標記
     * CONSUMED，讀到的值仍然有效。真正決定「誰贏得這次消耗」永遠看
     * {@link #consumeIfPending} 的回傳列數，不是這裡讀到的 status。
     *
     * <p>回傳 {@link OAuthTransactionView} 而非 entity 的理由見該 record 的說明：
     * 呼叫端不在交易內，用投影就不必為了 handler 的交易邊界把 {@code account}
     * 關聯改成 EAGER。
     *
     * <p>刻意寫明確的 {@code LEFT JOIN} 而不是 {@code t.account.id} 這種 path
     * navigation：LOGIN transaction 的 {@code account} 本來就是 null，而
     * {@code t.account.id} 會不會被 provider 最佳化成單純讀 FK（避免 inner join）
     * 屬於 Hibernate 的實作行為，不是 JPQL 規範保證的語意——不該讓「LOGIN 是否查得到」
     * 依賴這項最佳化有沒有生效。LEFT JOIN 只投影 Account 的 id 欄位，不會載入
     * 完整的 Account entity。
     */
    @Query("select new tw.com.ispan.gym.repository.account.OAuthTransactionView("
            + "t.stateHash, t.oauthProvider, t.purpose, t.status, a.id, t.returnPath, t.expiresAt) "
            + "from OAuthTransaction t left join t.account a where t.stateHash = :stateHash")
    Optional<OAuthTransactionView> findViewByStateHash(@Param("stateHash") String stateHash);

    /**
     * 原子消耗：只有真正把這一列從 PENDING 改成 CONSUMED 的呼叫方才算贏得這次
     * OAuth 往返的處理權，用回傳列數（0 或 1）判斷，而不是先查再改——避免併發的
     * 兩個 callback 都以為自己拿到了同一個 state。
     */
    @Modifying
    @Transactional
    @Query("update OAuthTransaction t set t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.CONSUMED, "
            + "t.finalizedAt = :now where t.stateHash = :stateHash "
            + "and t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.PENDING "
            + "and t.expiresAt > :now")
    int consumeIfPending(@Param("stateHash") String stateHash, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update OAuthTransaction t set t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.FAILED, "
            + "t.finalizedAt = :now, t.failureCode = :failureCode where t.stateHash = :stateHash "
            + "and t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.PENDING")
    int markFailedIfPending(@Param("stateHash") String stateHash, @Param("now") LocalDateTime now,
            @Param("failureCode") String failureCode);

    /**
     * LINK 發起時，把同一個 (account, provider, LINK) 尚未消耗的舊 transaction 全部
     * 標記作廢，確保同時間只有一筆有效——舊分頁 callback 回來會看到 SUPERSEDED，
     * 導向「請重新發起」而非誤判成攻擊。呼叫前必須先鎖 Account（見
     * GoogleOAuthLinkService），這裡本身不做鎖定，只負責這次的批次更新。
     */
    @Modifying
    @Transactional
    @Query("update OAuthTransaction t set t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.SUPERSEDED, "
            + "t.finalizedAt = :now where t.account.id = :accountId "
            + "and t.oauthProvider = tw.com.ispan.gym.enums.account.OAuthProvider.GOOGLE "
            + "and t.purpose = tw.com.ispan.gym.enums.account.OAuthTransactionPurpose.LINK "
            + "and t.status = tw.com.ispan.gym.enums.account.OAuthTransactionStatus.PENDING")
    int supersedePendingLinkTransactions(@Param("accountId") Integer accountId, @Param("now") LocalDateTime now);

    /** 排程清理：死亡（CONSUMED／FAILED／SUPERSEDED 的 finalizedAt，或自然過期的 expiresAt）滿保留期才刪 */
    @Modifying
    @Transactional
    @Query("delete from OAuthTransaction t where "
            + "(t.finalizedAt is not null and t.finalizedAt < :cutoff) "
            + "or (t.finalizedAt is null and t.expiresAt < :cutoff)")
    int deleteStaleTransactions(@Param("cutoff") LocalDateTime cutoff);
}
