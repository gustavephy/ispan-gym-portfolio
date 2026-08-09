package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Integer> {
    Page<Product> findByStatus(String status, Pageable pageable);
    
    Page<Product> findByTypeAndStatus(String type,String status,Pageable pageable);
    
    List<Product> findByStatus(String status);
    
    List<Product> findByTypeAndStatus(String type,String status);
    
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
    	@Param("type") String type,
    	@Param("status") String status,
    	@Param("categoryId") Integer categoryId,
    	@Param("keyword") String keyword,
    	Pageable pageable);
}

