package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.MemberCourseQuota;

public interface MemberCourseQuotaRepository extends JpaRepository<MemberCourseQuota, Integer> {
    /**
     * 找出某會員名下、對應某教練、目前可用的額度：還有剩餘堂數、且未過期
     * （expiryDate 為 null 代表永久有效，一併視為可用），依到期日由早到晚排序。
     * 呼叫端若拿多筆結果，應取第一筆（最早到期，優先用掉）。
     */

    @Query("SELECT q FROM MemberCourseQuota q WHERE q.member.id = :memberId AND q.trainer.trainerId = :trainerId "
            + "AND q.remainingCourse > 0 AND (q.expiryDate IS NULL OR q.expiryDate > CURRENT_TIMESTAMP) "
            + "ORDER BY q.expiryDate ASC")
    List<MemberCourseQuota> findEligibleQuota(@Param("memberId") Integer memberId, @Param("trainerId") Integer trainerId);
}
