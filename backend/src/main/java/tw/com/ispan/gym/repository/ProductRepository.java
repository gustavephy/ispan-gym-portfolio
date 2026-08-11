package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.Product;
import tw.com.ispan.gym.enums.ProductStatus;
import tw.com.ispan.gym.enums.ProductType;

public interface ProductRepository extends JpaRepository<Product, Integer> {
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
    
    Page<Product> findByTypeAndStatus(ProductType type,ProductStatus status,Pageable pageable);
    
    List<Product> findByStatus(ProductStatus status);
    
    List<Product> findByTypeAndStatus(ProductType type,ProductStatus status);
    
    @Query("SELECT p FROM Product p " +
           "WHERE p.status = :status " +
           "AND (:categoryId IS NULL OR p.productCategory.productCategoryId = :categoryId) " +
           "AND (:keyword IS NULL OR p.name LIKE %:keyword% OR p.description LIKE %:keyword% " +
           "OR p.flavor LIKE %:keyword% OR p.size LIKE %:keyword% " +
           "OR CAST(p.weight AS string) LIKE %:keyword% OR p.material LIKE %:keyword% OR p.origin LIKE %:keyword% " +
           "OR p.color LIKE %:keyword% OR p.brand LIKE %:keyword%)")
    Page<Product> findProductWithFilters(
    	@Param("type") ProductType type,
    	@Param("status") ProductStatus status,
    	@Param("categoryId") Integer categoryId,
    	@Param("keyword") String keyword,
    	Pageable pageable);
}

