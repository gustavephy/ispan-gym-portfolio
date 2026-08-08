package tw.com.ispan.gym.repository;

import tw.com.ispan.gym.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Integer> {}

