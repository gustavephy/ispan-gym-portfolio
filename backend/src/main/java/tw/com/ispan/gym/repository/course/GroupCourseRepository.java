package tw.com.ispan.gym.repository.course;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {

	/** 某教練授課的團課 */
	List<GroupCourse> findByTrainerId(Integer trainerId);

	List<GroupCourse> findByStatus(CourseStatus status);
}
