package tw.com.ispan.gym.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.CourseCategory;

public interface CourseCategoryRespository extends JpaRepository<CourseCategory, Integer> {
	Optional<CourseCategory> findByName(String name);
}
