package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface GroupCourseRepository extends JpaRepository<GroupCourse, Integer> {

	/** 某教練授課的團課；Trainer 主鍵欄位叫 trainerId，路徑須寫全名 */
	List<GroupCourse> findByTrainerTrainerId(Integer trainerId);

	List<GroupCourse> findByStatus(CourseStatus status);
}
