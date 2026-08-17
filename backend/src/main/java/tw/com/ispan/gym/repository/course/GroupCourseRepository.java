package tw.com.ispan.gym.repository.course;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {

	@Query("SELECT g FROM GroupCourse g WHERE g.trainer.trainerId = :trainerId")
	List<GroupCourse> findByTrainerId(Integer trainerId);

	List<GroupCourse> findByStatus(CourseStatus status);
}
