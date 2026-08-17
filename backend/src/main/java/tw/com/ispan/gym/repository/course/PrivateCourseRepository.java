package tw.com.ispan.gym.repository.course;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface PrivateCourseRepository extends JpaRepository<PrivateCourse, Integer> {

	/** 某教練授課的私教課 */
	List<PrivateCourse> findByTrainerId(Integer trainerId);

	List<PrivateCourse> findByStatus(CourseStatus status);
}
