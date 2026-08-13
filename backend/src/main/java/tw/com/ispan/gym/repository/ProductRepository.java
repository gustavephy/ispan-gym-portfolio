package tw.com.ispan.gym.repository;


import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tw.com.ispan.gym.entity.product.Product;
import tw.com.ispan.gym.enums.ProductStatus;

public interface ProductRepository extends JpaRepository<Product, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.productId = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Integer id);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    List<Product> findByStatus(ProductStatus status);

    @Query("SELECT p FROM Product p " +
           "WHERE p.status = :status " +
           "AND (:categoryId IS NULL OR p.productCategory.productCategoryId = :categoryId) " +
           "AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.description LIKE %:keyword% OR p.brand LIKE %:keyword%) " +
           "AND (:brand IS NULL OR p.brand = :brand) " +
           "AND (:flavor IS NULL OR p.flavor = :flavor) " +
           "AND (:size IS NULL OR p.size = :size) " +
           "AND (:minPrice IS NULL OR p.price >= :minPrice) " +
           "AND (:maxPrice IS NULL OR p.price <= :maxPrice)")
    Page<Product> findProductsWithFilters(
    	@Param("status") ProductStatus status,
    	@Param("categoryId") Integer categoryId,
    	@Param("keyword") String keyword,
    	@Param("brand") String brand,
    	@Param("flavor") String flavor,
    	@Param("size") String size,
    	@Param("minPrice") BigDecimal minPrice,
    	@Param("maxPrice") BigDecimal maxPrice,
    	Pageable pageable);

    /** 原子扣庫存；stock 不足時不更新（回傳 0），由呼叫端轉為業務例外 */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :quantity WHERE p.productId = :id AND p.stock >= :quantity")
    int decrementStock(@Param("id") Integer id, @Param("quantity") Integer quantity);
}
