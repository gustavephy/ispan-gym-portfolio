package tw.com.ispan.gym.repository.employee;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

public interface EmployeeRepository extends JpaRepository<Employee, Integer> {

	/** employee_id 與 account_id 已脫鉤（獨立流水號），由登入帳號反查員工資料一律走這裡 */
	Optional<Employee> findByAccountId(Integer accountId);

	List<Employee> findByStatusAndDeletedAtIsNull(EmployeeStatus status);

	List<Employee> findByRoleAndStatusAndDeletedAtIsNull(EmployeeRole role, EmployeeStatus status);
}
