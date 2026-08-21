package tw.com.ispan.gym.repository.trainer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

public interface TrainerRepository extends JpaRepository<Trainer, Integer> {

	/** 依員工帳號查教練檔案；一位員工至多一筆（不分軟刪除，建立前的重複檢查要用這個） */
	Optional<Trainer> findByEmployeeId(Integer employeeId);

	List<Trainer> findByStatusAndDeletedAtIsNull(TrainerStatus status);

	/** 公開教練列表：只排除軟刪除，不篩 status（ACTIVE/UNAVAILABLE 皆公開可見） */
	List<Trainer> findByDeletedAtIsNull();

	/** 教練本人 PUT 用：依 employeeId 悲觀鎖定，不分軟刪除（Service 自行判斷是否可用） */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trainer t where t.employee.id = :employeeId")
	Optional<Trainer> findByEmployeeIdForUpdate(@Param("employeeId") Integer employeeId);

	/**
	 * 純量投影，只取 id，不建立完整的 Trainer 實體物件。§9 跨模組防護的建立流程
	 * （CoachSlotService.createSlot 等）需要先用 employeeId 解出 trainerId，再交給
	 * TrainerService.lockAndAssertAvailableForNewBusiness() 做唯一一次悲觀鎖定讀取——
	 * 若在那之前用一般查詢先把完整 Trainer 實體撈進同一個 persistence context，
	 * 之後的鎖定查詢會命中 Hibernate 的 identity map、直接回傳先前那個未鎖定讀到的
	 * 舊物件，鎖形同虛設。純量投影不會把實體放進 persistence context，可以安全地
	 * 用來解出 id 而不影響後續的鎖定讀取。
	 */
	@Query("select t.id from Trainer t where t.employee.id = :employeeId")
	Optional<Integer> findIdByEmployeeId(@Param("employeeId") Integer employeeId);

	/** ADMIN 修改／狀態切換／停用／恢復共用：依 trainerId 悲觀鎖定，不分軟刪除 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trainer t where t.id = :trainerId")
	Optional<Trainer> findByIdForUpdate(@Param("trainerId") Integer trainerId);

	/**
	 * ADMIN 教練列表：關鍵字不分大小寫比對姓名／登入 email／專長，deleted 篩是否只看已停用，
	 * status 可篩（不傳＝全部）。JOIN FETCH employee／account 避免映射時 N+1，
	 * 明確指定 countQuery 理由同 EmployeeRepository.findEmployeesForAdmin()。
	 */
	@Query(value = "SELECT t FROM Trainer t "
			+ "JOIN FETCH t.employee e "
			+ "JOIN FETCH e.account a "
			+ "WHERE ((:deleted = true AND t.deletedAt IS NOT NULL) OR (:deleted = false AND t.deletedAt IS NULL)) "
			+ "AND (:status IS NULL OR t.status = :status) "
			+ "AND (:keyword IS NULL "
			+ "     OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(CONCAT(e.lastName, e.firstName)) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(t.specialty) LIKE LOWER(CONCAT('%', :keyword, '%')))",
			countQuery = "SELECT COUNT(t) FROM Trainer t "
			+ "JOIN t.employee e "
			+ "JOIN e.account a "
			+ "WHERE ((:deleted = true AND t.deletedAt IS NOT NULL) OR (:deleted = false AND t.deletedAt IS NULL)) "
			+ "AND (:status IS NULL OR t.status = :status) "
			+ "AND (:keyword IS NULL "
			+ "     OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(CONCAT(e.lastName, e.firstName)) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(t.specialty) LIKE LOWER(CONCAT('%', :keyword, '%')))")
	Page<Trainer> findTrainersForAdmin(
			@Param("keyword") String keyword,
			@Param("status") TrainerStatus status,
			@Param("deleted") boolean deleted,
			Pageable pageable);
}
