package tw.com.ispan.gym.repository.course;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {

	/** 某教練授課的團課 */
	List<GroupCourse> findByTrainerId(Integer trainerId);

	List<GroupCourse> findByStatus(CourseStatus status);

	/**
	 * 後台團課主檔列表的分頁＋文字搜尋，比對課程名稱與授課教練姓名。
	 * 用 @Query 手寫 JPQL 而非衍生查詢方法名稱，因為要 join 到 Employee 比對姓名，
	 * 方法名稱推導不出這種跨關聯的比對條件。
	 *
	 * 故意不在這裡寫 ORDER BY——排序交給呼叫端組出的 Pageable 的 Sort 決定，
	 * 避免 JPQL 手寫的 ORDER BY 跟 Pageable 帶的 Sort 疊加或衝突。
	 */
	@Query("""
			SELECT c FROM GroupCourse c
			JOIN c.trainer t
			JOIN t.employee e
			WHERE :search IS NULL OR :search = ''
				OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
				OR LOWER(CONCAT(e.firstName, e.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
			""")
	Page<GroupCourse> searchCourses(@Param("search") String search, Pageable pageable);

	/**
	 * 公開團課介紹頁用的分頁＋文字搜尋，只回傳上架中（status=ACTIVE）的課程。
	 * 刻意獨立一個方法而不是在 {@link #searchCourses} 上加 status 條件——那支是後台主檔列表
	 * （不分狀態，管理員要看得到已下架的課程）在用，加了 status 篩選會意外把後台列表也
	 * 限縮成只剩 ACTIVE，兩邊語意不同，分開寫才不會互相影響。
	 */
	@Query("""
			SELECT c FROM GroupCourse c
			JOIN c.trainer t
			JOIN t.employee e
			WHERE c.status = :status
				AND (:search IS NULL OR :search = ''
					OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
					OR LOWER(CONCAT(e.firstName, e.lastName)) LIKE LOWER(CONCAT('%', :search, '%')))
			""")
	Page<GroupCourse> searchActiveCourses(@Param("status") CourseStatus status, @Param("search") String search,
			Pageable pageable);
}
