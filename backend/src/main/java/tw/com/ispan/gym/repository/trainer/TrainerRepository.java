package tw.com.ispan.gym.repository.trainer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

public interface TrainerRepository extends JpaRepository<Trainer, Integer> {

	/** 依員工帳號查教練檔案；一位員工至多一筆（不分軟刪除，建立前的重複檢查要用這個） */
	Optional<Trainer> findByEmployeeEmployeeId(Integer employeeId);

	List<Trainer> findByStatusAndDeletedAtIsNull(TrainerStatus status);

	/** 公開教練列表：只排除軟刪除，不篩 status（ACTIVE/UNAVAILABLE 皆公開可見） */
	List<Trainer> findByDeletedAtIsNull();
}
