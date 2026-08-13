package tw.com.ispan.gym.repository.product;


import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.product.ProductCategory;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Integer> {

	Optional<ProductCategory> findByName(String name);
}
