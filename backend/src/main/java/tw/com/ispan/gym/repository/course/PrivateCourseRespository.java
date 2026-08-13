package tw.com.ispan.gym.repository.course;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface PrivateCourseRespository extends JpaRepository<PrivateCourse, Integer> {
	List<PrivateCourse> findByTrainerId(Integer trainerId);
	List<PrivateCourse> findByStatus(CourseStatus status);
}
