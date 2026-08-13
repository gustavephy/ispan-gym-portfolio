package tw.com.ispan.gym.repository;


import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.product.InventoryLog;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, Integer> {
	// Product 的主鍵欄位叫 productId，衍生查詢路徑須寫全名 product.productId
	List<InventoryLog> findByProductProductIdOrderByCreatedAtDesc(Integer productId);
	
	List<InventoryLog> findByChangeTypeOrderByCreatedAtDesc(String changeType);
}
