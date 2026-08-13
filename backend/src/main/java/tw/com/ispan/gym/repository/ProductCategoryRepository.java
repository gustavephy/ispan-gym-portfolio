package tw.com.ispan.gym.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.ProductCategory;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Integer> {

	Optional<ProductCategory> findByName(String name);
}
