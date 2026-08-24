package tw.com.ispan.gym.repository.employee;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

public interface EmployeeRepository extends JpaRepository<Employee, Integer> {

	/** employee_id 與 account_id 已脫鉤（獨立流水號），由登入帳號反查員工資料一律走這裡 */
	Optional<Employee> findByAccountId(Integer accountId);

	List<Employee> findByStatusAndDeletedAtIsNull(EmployeeStatus status);

	List<Employee> findByRoleAndStatusAndDeletedAtIsNull(EmployeeRole role, EmployeeStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.employeeId = :id")
	Optional<Employee> findByIdForUpdate(@Param("id") Integer id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.account.id = :accountId")
	Optional<Employee> findByAccountIdForUpdate(@Param("accountId") Integer accountId);

	/**
	 * 悲觀鎖定整組有效 ADMIN（role=ADMIN 且 status=ACTIVE 且未軟刪除），依 employeeId 升冪排序。
	 * 降級／停用會減少有效 ADMIN 數量的操作皆須先鎖定這個集合再檢查數量，且鎖定順序必須固定
	 * 為 employeeId ASC，避免兩個併發交易以相反順序鎖定造成 deadlock（見 employee_management_phase_scope）。
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Employee> findByRoleAndStatusAndDeletedAtIsNullOrderByEmployeeIdAsc(EmployeeRole role, EmployeeStatus status);

	/**
	 * ADMIN 員工列表：關鍵字不分大小寫比對姓名／手機／登入 email，狀態與角色可篩選（不傳＝全部），
	 * 一律排除軟刪除。用 fetch-join 帶出 account，避免映射 AdminEmployeeSummaryResponse 時
	 * 逐筆觸發 lazy load 造成 N+1。明確指定 countQuery，避免 Spring Data 對含 JOIN FETCH 的
	 * @Query 自動推導分頁計數查詢時出現疑慮。比照 MemberRepository.findMembersForAdmin()，
	 * 額外多比對 CONCAT(lastName, firstName)——只比對 firstName／lastName 各自欄位時，
	 * 輸入完整顯示姓名（如「王小美」）會搜不到，這裡補上完整姓名比對。
	 */
	@Query(value = "SELECT e FROM Employee e "
			+ "JOIN FETCH e.account a "
			+ "WHERE e.deletedAt IS NULL "
			+ "AND (:status IS NULL OR e.status = :status) "
			+ "AND (:role IS NULL OR e.role = :role) "
			+ "AND (:keyword IS NULL "
			+ "     OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(CONCAT(e.lastName, e.firstName)) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')))",
			countQuery = "SELECT COUNT(e) FROM Employee e "
			+ "JOIN e.account a "
			+ "WHERE e.deletedAt IS NULL "
			+ "AND (:status IS NULL OR e.status = :status) "
			+ "AND (:role IS NULL OR e.role = :role) "
			+ "AND (:keyword IS NULL "
			+ "     OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(CONCAT(e.lastName, e.firstName)) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(e.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) "
			+ "     OR LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')))")
	Page<Employee> findEmployeesForAdmin(
			@Param("keyword") String keyword,
			@Param("status") EmployeeStatus status,
			@Param("role") EmployeeRole role,
			Pageable pageable);
}
