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

	@Query("SELECT g FROM GroupCourse g WHERE g.trainer.id = :trainerId")
	List<GroupCourse> findByTrainerId(Object trainerId);

	List<GroupCourse> findByStatus(CourseStatus status);

	// 依狀態、教練 ID 與關鍵字搜尋團體課程，支援分頁與日期範圍篩選
	@Query("SELECT g FROM GroupCourse g " +
			"WHERE (:status IS NULL OR g.status = :status) " +
			"AND (:trainerId IS NULL OR g.trainer.id = :trainerId) " +
			"AND (:keyword IS NULL OR g.name LIKE CONCAT('%', :keyword, '%') OR g.trainer.lastName LIKE CONCAT('%', :keyword, '%') OR g.trainer.firstName LIKE CONCAT('%', :keyword, '%')) "
			+
			"AND (:startDate IS NULL OR g.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR g.createdAt <= :endDate)")
	Page<GroupCourse> findCoursesWithFilters(
			@Param("status") CourseStatus status,
			@Param("trainerId") Integer trainerId,
			@Param("keyword") String keyword,
			@Param("startDate") LocalDateTime startDate,
			@Param("endDate") LocalDateTime endDate,
			Pageable pageable);

}
