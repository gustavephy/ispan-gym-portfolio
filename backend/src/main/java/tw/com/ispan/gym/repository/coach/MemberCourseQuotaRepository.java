package tw.com.ispan.gym.repository.coach;


import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.coach.MemberCourseQuota;

public interface MemberCourseQuotaRepository extends JpaRepository<MemberCourseQuota, Integer> {
    /**
     * 找出某會員名下、對應某教練、目前可用的額度：還有剩餘堂數、且未過期
     * （expiryDate 為 null 代表永久有效，一併視為可用），依到期日由早到晚排序。
     * 呼叫端若拿多筆結果，應取第一筆（最早到期，優先用掉）。
     */

    @Query("SELECT q FROM MemberCourseQuota q WHERE q.member.id = :memberId AND q.trainer.id = :trainerId "
            + "AND q.remainingCourse > 0 AND (q.expiryDate IS NULL OR q.expiryDate > CURRENT_TIMESTAMP) "
            + "ORDER BY q.expiryDate ASC")
    List<MemberCourseQuota> findEligibleQuota(@Param("memberId") Integer memberId, @Param("trainerId") Integer trainerId);

    /**
     * 查詢某會員名下的所有課程包額度（不篩選狀態，ACTIVE/DEPLETED/EXPIRED 都回傳，
     * 由呼叫端或前端自行判斷要不要篩選），依到期日由早到晚排序
     * （expiryDate 為 null 視為永久有效，排在最後）。
     * 會員查自己、管理者查指定會員，共用這個方法，差異只在呼叫端傳入的 memberId
     * 是否受信任，不在查詢邏輯本身。
     */
    @Query("SELECT q FROM MemberCourseQuota q WHERE q.member.id = :memberId "
            + "ORDER BY CASE WHEN q.expiryDate IS NULL THEN 1 ELSE 0 END, q.expiryDate ASC")
    Page<MemberCourseQuota> findByMemberId(@Param("memberId") Integer memberId, Pageable pageable);
}
