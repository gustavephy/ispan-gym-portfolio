package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.PrivateCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;

public interface PrivateCourseRepository extends JpaRepository<PrivateCourse, Integer> {

	/** Trainer 的主鍵欄位叫 trainerId，衍生查詢路徑須寫全名 trainer.trainerId */
	List<PrivateCourse> findByTrainerTrainerId(Integer trainerId);

	List<PrivateCourse> findByStatus(CourseStatus status);
}
