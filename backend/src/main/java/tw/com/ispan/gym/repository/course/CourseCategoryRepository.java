package tw.com.ispan.gym.repository.course;


import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.course.CourseCategory;

public interface CourseCategoryRepository extends JpaRepository<CourseCategory, Integer> {
	Optional<CourseCategory> findByName(String name);
}
