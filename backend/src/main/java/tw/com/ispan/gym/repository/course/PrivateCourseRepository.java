package tw.com.ispan.gym.repository.course;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface PrivateCourseRepository extends JpaRepository<PrivateCourse, Integer> {
	@Query("SELECT p FROM PrivateCourse p WHERE p.trainer.trainerId = :trainerId")
	List<PrivateCourse> findByTrainerId(Integer trainerId);

	List<PrivateCourse> findByStatus(CourseStatus status);

	// 依狀態、教練 ID 與關鍵字搜尋私教課程，支援分頁與日期範圍篩選
	@Query("SELECT p FROM PrivateCourse p " +
			"WHERE (:status IS NULL OR p.status = :status) " +
			"AND (:trainerId IS NULL OR p.trainer.trainerId = :trainerId) " +
			"AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.trainer.lastName LIKE %:keyword% OR p.trainer.firstName LIKE %:keyword%) "
			+
			"AND (:startDate IS NULL OR p.createdAt >= :startDate) " +
			"AND (:endDate IS NULL OR p.createdAt <= :endDate)")
	Page<PrivateCourse> findCoursesWithFilters(
			@Param("status") CourseStatus status,
			@Param("trainerId") Integer trainerId,
			@Param("keyword") String keyword,
			@Param("startDate") LocalDateTime startDate,
			@Param("endDate") LocalDateTime endDate,
			Pageable pageable);

}
