package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.Course;

public interface CourseRepository extends JpaRepository<Course, Integer> {
//	List<Course> findByTrainerId(Integer trainerId);
//	List<Course> findByStatus(String status);
}
