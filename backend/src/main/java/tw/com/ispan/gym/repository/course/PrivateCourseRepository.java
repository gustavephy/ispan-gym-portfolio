package tw.com.ispan.gym.repository.course;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface PrivateCourseRepository extends JpaRepository<PrivateCourse, Integer> {
	@Query("SELECT p FROM PrivateCourse p WHERE p.trainer.trainerId = :trainerId")
	List<PrivateCourse> findByTrainerId(Integer trainerId);

	List<PrivateCourse> findByStatus(CourseStatus status);

	@Query("SELECT p FROM PrivateCourse p " +
			"WHERE (:status IS NULL OR p.status = :status) " +
			"AND (:trainerId IS NULL OR p.trainer.trainerId = :trainerId) " +
			"AND (:categoryId IS NULL OR p.category.courseCategoryId = :categoryId) " +
			"AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.trainer.lastName LIKE %:keyword% OR p.trainer.firstName LIKE %:keyword%) " +
			"AND (:startDate IS NULL OR p.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR p.createdAt <= :endDate) " +
			"ORDER BY p.privateCourseId DESC")
	List<PrivateCourse> findCoursesWithFilters(
			@Param("status") CourseStatus status,
			@Param("trainerId") Integer trainerId,
			@Param("categoryId") Integer categoryId,
			@Param("keyword") String keyword,
			@Param("startDate") LocalDateTime startDate,
			@Param("endDate") LocalDateTime endDate);

	/**
	 * 教練停用前檢查用：名下還有幾筆生效中（未下架）的私教課商品。trainer_id 是必填 FK，
	 * 商品本身沒有「解除指派」的中間狀態，故只看 status，不看日期——本表沒有到期日欄位
	 * （見 trainer_management_next_phase 規格基準的核對結果）。
	 */
	long countByTrainerTrainerIdAndStatus(Integer trainerId, CourseStatus status);

	/**
	 * AI 課程推薦（ChatAiResponderService）專用：category 是 LAZY 關聯，該服務組 prompt
	 * 時要讀 category 名稱，但查詢與讀取分屬呼叫 Gemini 前後兩段（刻意不把整個非同步方法包
	 * 在同一個交易裡，避免佔用 DB 連線等 Gemini 回應），一般的 findByStatus 查完後 session
	 * 就關閉，此時才讀 category 會拋 LazyInitializationException。用 JOIN FETCH 把
	 * category 隨查詢一次帶出，離開這支方法後即使 session 關閉也已是可用資料。
	 */
	@Query("SELECT c FROM PrivateCourse c LEFT JOIN FETCH c.category WHERE c.status = :status")
	List<PrivateCourse> findByStatusWithCategory(@Param("status") CourseStatus status);
}
