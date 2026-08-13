package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {

	List<GroupCourse> findByStatus(CourseStatus status);

	/** 開放訂閱者報名的團課列表 */
	List<GroupCourse> findByStatusAndIsOpenForSubscriptionTrue(CourseStatus status);
}
