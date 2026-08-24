package tw.com.ispan.gym.repository.course;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {


	// 依狀態、教練 ID 與關鍵字搜尋團體課程，支援分頁與日期範圍篩選
	// 姓名欄位在 Employee，需 JOIN trainer → employee 才能比對（同 searchCourses 的做法）
	// ▶ 呼叫端：前台 GET /api/group-courses/public（CourseView、商城）
	@Query("SELECT g FROM GroupCourse g " +
			"JOIN g.trainer t " +
			"JOIN t.employee e " +
			"WHERE (:status IS NULL OR g.status = :status) " +
			"AND (:trainerId IS NULL OR t.trainerId = :trainerId) " +
			"AND (:keyword IS NULL OR g.name LIKE CONCAT('%', :keyword, '%') " +
			"     OR LOWER(CONCAT(e.firstName, e.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
			"AND (:startDate IS NULL OR g.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR g.createdAt <= :endDate)")
	Page<GroupCourse> findCoursesWithFilters(
			@Param("status") CourseStatus status,
			@Param("trainerId") Integer trainerId,
			@Param("keyword") String keyword,
			@Param("startDate") LocalDateTime startDate,
			@Param("endDate") LocalDateTime endDate,
			Pageable pageable);

	// AI 課程推薦（ChatAiResponderService）專用：category 是 LAZY 關聯，該服務組 prompt
	// 時要讀 category 名稱，但查詢與讀取分屬呼叫 Gemini 前後兩段（刻意不把整個非同步方法包
	// 在同一個交易裡，避免佔用 DB 連線等 Gemini 回應），一般的 findByStatus 查完後 session
	// 就關閉，此時才讀 category 會拋 LazyInitializationException。用 JOIN FETCH 把
	// category 隨查詢一次帶出，離開這支方法後即使 session 關閉也已是可用資料。
	// ▶ 呼叫端：內部 ChatAiResponderService（非 HTTP 直接呼叫）
	@Query("SELECT c FROM GroupCourse c LEFT JOIN FETCH c.category WHERE c.status = :status")
	List<GroupCourse> findByStatusWithCategory(@Param("status") CourseStatus status);

	// 後台團課主檔列表的分頁＋文字搜尋，比對課程名稱與授課教練姓名。
	// 用 @Query 手寫 JPQL 而非衍生查詢方法名稱，因為要 join 到 Employee 比對姓名，
	// 方法名稱推導不出這種跨關聯的比對條件。
	// 故意不在這裡寫 ORDER BY——排序交給呼叫端組出的 Pageable 的 Sort 決定，
	// 避免 JPQL 手寫的 ORDER BY 跟 Pageable 帶的 Sort 疊加或衝突。
	// ▶ 呼叫端：後台 AdminGroupCourseController（getAllCourses 分頁搜尋）
	@Query("""
			SELECT c FROM GroupCourse c
			JOIN c.trainer t
			JOIN t.employee e
			WHERE :search IS NULL OR :search = ''
				OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
				OR LOWER(CONCAT(e.firstName, e.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
			""")
	Page<GroupCourse> searchCourses(@Param("search") String search, Pageable pageable);

}
