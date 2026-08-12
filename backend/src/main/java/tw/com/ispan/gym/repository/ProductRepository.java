package tw.com.ispan.gym.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.enums.ProductStatus;

public interface ProductRepository extends JpaRepository<Product, Integer> {
	Page<Product> findByStatus(ProductStatus status, Pageable pageable);

	List<Product> findByStatus(ProductStatus status);

	@Query("SELECT DISTINCT p FROM Product p " + "WHERE p.status = :status "
			+ "AND (:categoryId IS NULL OR p.productCategory.id = :categoryId) "
			+ "AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.description LIKE %:keyword% OR p.brand LIKE %:keyword%) "
			+ "AND (:brand IS NULL OR p.brand = :brand) " + "AND (:flavor IS NULL OR p.flavor = :flavor) "
			+ "AND (:size IS NULL OR p.size = :size) " + "AND (:minPrice IS NULL OR p.price >= :minPrice) "
			+ "AND (:maxPrice IS NULL OR p.price <= :maxPrice)")
	Page<Product> findProductsWithFilters(@Param("status") ProductStatus status,
			@Param("categoryId") Integer categoryId, @Param("keyword") String keyword, @Param("brand") String brand,
			@Param("flavor") String flavor, @Param("size") String size,
			@Param("minPrice") java.math.BigDecimal minPrice, @Param("maxPrice") java.math.BigDecimal maxPrice,
			Pageable pageable);

	@org.springframework.data.jpa.repository.Modifying
	@Query("UPDATE Product p SET p.stock = p.stock - :quantity WHERE p.id = :id AND p.stock >= :quantity")
	int decrementStock(@Param("id") Integer id, @Param("quantity") Integer quantity);
}
