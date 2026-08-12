package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.InventoryLog;

public interface InventoryLogRepository extends JpaRepository<InventoryLog, Integer> {
	List<InventoryLog> findByProductIdOrderByCreatedAtDesc(Integer productId);
	
	List<InventoryLog> findByChangeTypeOrderByCreatedAtDesc(String changeType);
}
