package tw.com.ispan.gym.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.TrainerStatus;

public interface TrainerRepository extends JpaRepository<Trainer, Integer> {

	/** 依員工帳號查教練檔案；一位員工至多一筆 */
	Optional<Trainer> findByEmployeeId(Integer employeeId);

	List<Trainer> findByStatusAndDeletedAtIsNull(TrainerStatus status);
}
