package tw.com.ispan.gym.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;

public interface ProductRepository extends JpaRepository<Product, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Integer id);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByTypeAndStatus(ProductType type, ProductStatus status, Pageable pageable);

    List<Product> findByStatus(ProductStatus status);

    List<Product> findByTypeAndStatus(ProductType type, ProductStatus status);

    @Query("SELECT DISTINCT p FROM Product p " +
           "LEFT JOIN ProductSku s ON s.product.id = p.id " +
           "WHERE p.status = :status " +
           "AND (:type IS NULL OR p.type = :type) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.description LIKE %:keyword% " +
           "OR s.skuName LIKE %:keyword% OR s.flavor LIKE %:keyword% OR s.size LIKE %:keyword% " +
           "OR CAST(s.weight AS string) LIKE %:keyword% OR s.material LIKE %:keyword% OR s.origin LIKE %:keyword% " +
           "OR s.color LIKE %:keyword% OR s.brand LIKE %:keyword%)")
    Page<Product> findProductWithFilters(
    	@Param("type") ProductType type,
    	@Param("status") ProductStatus status,
    	@Param("categoryId") Integer categoryId,
    	@Param("keyword") String keyword,
    	Pageable pageable);
}
