package tw.com.ispan.gym.repository.account;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.account.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Integer> {

    /**
     * 悲觀鎖讀取：同一個 token 兩個並行的重設請求只能有一個成功，
     * 另一個在鎖釋放後重讀會看到 usedAt 已被設定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 非鎖定 scalar 查詢：只用來在鎖 Account 之前先定位 accountId，鎖序需求是
     * 先鎖 Account、再鎖 token（帳號安全域的全域鎖序），但一開始只有 token、
     * 還不知道 accountId，所以要先用不鎖的查詢拿到它。這次讀取結果不能當
     * 有效性或授權依據——所有狀態都必須在後續 {@link #findByTokenHashForUpdate}
     * 取得鎖之後重新驗證一次。
     *
     * <p><b>刻意回傳 scalar {@code Integer} 而不是整個 Entity</b>：同一個 transaction
     * 的 persistence context 內，Hibernate 對同一個 id 的重複查詢會回傳同一個
     * 已受管理的 instance（identity map），欄位不會因為第二次查詢改了 lock mode
     * 就重新從 DB 覆寫。如果這裡回傳完整 Entity，會讓它先被放進 persistence
     * context，之後 {@link #findByTokenHashForUpdate} 即使真的下了
     * {@code SELECT ... FOR UPDATE}，Java 端拿到的仍可能是同一個舊 instance，
     * 「鎖內重新驗證」就形同虛設。回傳 scalar 值可以確保 Entity 只在鎖定查詢時
     * 才第一次進入 persistence context，欄位值保證來自鎖定後的最新讀取。
     */
    @Query("select t.account.id from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<Integer> findAccountIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** 新申請要作廢的「同帳號、目前仍算有效」的舊 token */
    List<PasswordResetToken> findByAccountIdAndUsedAtIsNullAndInvalidatedAtIsNull(Integer accountId);

    /** 重設成功時作廢同帳號「其他」仍有效的 token，排除自己——不依賴 flush 時機推測是否會抓到自己 */
    List<PasswordResetToken> findByAccountIdAndIdNotAndUsedAtIsNullAndInvalidatedAtIsNull(Integer accountId,
            Integer excludeTokenId);

    /**
     * 排程清理用：已經沒有業務用途（已使用／已作廢／已自然過期三種之一）的舊列，
     * 死亡後滿保留期才真的刪除——「保留期」是指從「變成沒有用」那一刻起算，不是從
     * {@code createdAt} 起算（code review 抓到的原始版本 bug：用 createdAt 算會讓一個
     * 建立很久、但今天才被作廢的 token 立刻被刪，跟「死亡後留七天供排查」的本意不符）。
     * 三種死法的判斷各自對到各自的時間欄位：已使用看 usedAt、已作廢看 invalidatedAt、
     * 兩者都沒有但已經自然過期看 expiresAt。
     */
    @Modifying
    @Query("delete from PasswordResetToken t where "
            + "(t.usedAt is not null and t.usedAt < :cutoff) "
            + "or (t.invalidatedAt is not null and t.invalidatedAt < :cutoff) "
            + "or (t.usedAt is null and t.invalidatedAt is null and t.expiresAt < :cutoff)")
    int deleteStaleTokens(@Param("cutoff") LocalDateTime cutoff);
}
