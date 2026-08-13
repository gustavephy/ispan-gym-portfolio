package tw.com.ispan.gym.repository.product;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.product.InventoryLog;
import tw.com.ispan.gym.enums.product.InventoryChangeType;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, Integer> {
	List<InventoryLog> findByProductIdOrderByCreatedAtDesc(Integer productId);
	
	List<InventoryLog> findByChangeTypeOrderByCreatedAtDesc(InventoryChangeType changeType);
}
