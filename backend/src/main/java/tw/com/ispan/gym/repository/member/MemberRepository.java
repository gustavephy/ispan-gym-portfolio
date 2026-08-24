package tw.com.ispan.gym.repository.member;

import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    /** member_id 與 account_id 已脫鉤（獨立流水號），由登入帳號反查會員資料一律走這裡 */
    Optional<Member> findByAccountId(Integer accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Member u where u.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Integer id);

    /** 期間新註冊會員數（報表用）；半開區間 [from, to) */
    @Query("SELECT COUNT(m) FROM Member m WHERE m.createdAt >= :from AND m.createdAt < :to")
    long countCreatedBetween(@Param("from") java.time.LocalDateTime from,
                             @Param("to") java.time.LocalDateTime to);

    /**
     * 依 account id 悲觀鎖定：自助修改個資專用。刻意不透過 findByAccountId() 先載入，
     * 避免同一 transaction 內 Member 被 Persistence Context 快取後，之後即使改用鎖定查詢
     * 命中同一列，Hibernate 仍回傳先前那個未鎖定讀到的舊物件、不會用新結果覆蓋欄位值，
     * 讓鎖形同白鎖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.account.id = :accountId")
    Optional<Member> findByAccountIdForUpdate(@Param("accountId") Integer accountId);

    /**
     * ADMIN 會員列表：關鍵字不分大小寫比對姓名／手機／登入 email，狀態可篩選（不傳＝全部），一律排除軟刪除。
     * 用 fetch-join 帶出 account，避免映射 AdminMemberSummaryResponse 時逐筆觸發 lazy load 造成 N+1。
     * 明確指定 countQuery，避免 Spring Data 對含 JOIN FETCH 的 @Query 自動推導分頁計數查詢時出現疑慮。
     */
    @Query(value = "SELECT m FROM Member m "
            + "JOIN FETCH m.account a "
            + "WHERE m.deletedAt IS NULL "
            + "AND (:status IS NULL OR m.status = :status) "
            + "AND (:keyword IS NULL "
            + "     OR LOWER(m.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(m.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(m.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')))",
            countQuery = "SELECT COUNT(m) FROM Member m "
            + "JOIN m.account a "
            + "WHERE m.deletedAt IS NULL "
            + "AND (:status IS NULL OR m.status = :status) "
            + "AND (:keyword IS NULL "
            + "     OR LOWER(m.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(m.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(m.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Member> findMembersForAdmin(
            @Param("keyword") String keyword,
            @Param("status") UserStatus status,
            Pageable pageable);
}
