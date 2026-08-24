package tw.com.ispan.gym.repository.account;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.enums.account.OAuthProvider;

public interface OAuthRegistrationTicketRepository extends JpaRepository<OAuthRegistrationTicket, Integer> {

    /** 唯讀查詢：讀取 provider/subject/email 等建立後不再變動的欄位；不能當有效性判斷 */
    Optional<OAuthRegistrationTicket> findByTokenHash(String tokenHash);

    /**
     * 悲觀鎖讀取：ticket 的消耗必須和 Account／Member／OauthAccount 的建立在同一個
     * transaction 內完成，否則中途失敗會留下「ticket 已 USED、但帳號沒建成」的
     * 不可回復狀態，使用者也無法重試。改用鎖定查詢而不是先前的獨立 UPDATE：
     * 鎖住這一列後在同一交易內驗證狀態、建立資料、最後才標記 USED，任何一步失敗
     * 都會連同 USED 一起 rollback，ticket 保持 PENDING 可重試。
     *
     * <p>同一張 ticket 的兩個並行請求會在這裡序列化，後到的那個取得鎖時會看到
     * status 已是 USED，直接視為無效。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OAuthRegistrationTicket t where t.tokenHash = :tokenHash")
    Optional<OAuthRegistrationTicket> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** 同一個 (provider, subject) 重新核發 ticket 時，作廢舊的未使用 ticket，只留最新一張有效 */
    @Modifying
    @Transactional
    @Query("update OAuthRegistrationTicket t set t.status = tw.com.ispan.gym.enums.account.OAuthRegistrationTicketStatus.SUPERSEDED, "
            + "t.finalizedAt = :now where t.oauthProvider = :provider and t.providerUserId = :subject "
            + "and t.status = tw.com.ispan.gym.enums.account.OAuthRegistrationTicketStatus.PENDING")
    int supersedePendingTickets(@Param("provider") OAuthProvider provider, @Param("subject") String subject,
            @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from OAuthRegistrationTicket t where "
            + "(t.finalizedAt is not null and t.finalizedAt < :cutoff) "
            + "or (t.finalizedAt is null and t.expiresAt < :cutoff)")
    int deleteStaleTickets(@Param("cutoff") LocalDateTime cutoff);
}
