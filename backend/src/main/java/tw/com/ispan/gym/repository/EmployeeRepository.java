package tw.com.ispan.gym.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.Employee;
import tw.com.ispan.gym.enums.EmployeeRole;
import tw.com.ispan.gym.enums.EmployeeStatus;

public interface EmployeeRepository extends JpaRepository<Employee, Integer> {

	List<Employee> findByStatusAndDeletedAtIsNull(EmployeeStatus status);

	List<Employee> findByRoleAndStatusAndDeletedAtIsNull(EmployeeRole role, EmployeeStatus status);
}
