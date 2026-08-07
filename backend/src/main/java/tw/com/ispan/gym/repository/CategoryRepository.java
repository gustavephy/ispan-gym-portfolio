package tw.com.ispan.gym.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tw.com.ispan.gym.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Integer> {
	
}
