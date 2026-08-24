package tw.com.ispan.gym.service.employee;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.employee.EmployeeProfileResponse;
import tw.com.ispan.gym.dto.employee.EmployeeProfileUpdateRequest;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 員工自助查詢／修改個人資料。
 *
 * <p>GET／PUT 都掛 hasRole("EMPLOYEE")（見 EmployeeProfileController），INACTIVE 員工
 * 在安全層就會被擋下、進不到這裡——跟 MemberProfileService 不同：Member 的自助 GET
 * 只要求已登入（SUSPENDED 仍可查自己狀態），所以那邊的 Service 必須自己分辨「查得到但
 * 不能改」的中間狀態。Employee 這裡兩個方法對 ACTIVE 的要求是一致的，仍各自防禦性
 * 檢查一次，避免完全依賴安全層那一瞬間的判斷（見下方 updateMyProfile 的說明）。
 *
 * <p>employeeId／accountId 一律由呼叫端（Controller）從登入資訊解出並傳入，Service
 * 不依賴 SecurityContext。PUT 傳 accountId 而非 employeeId——比照 MemberProfileService：
 * 若 Controller 先透過 requireCurrentEmployeeId()（內部會先用未鎖定查詢載入 Employee）
 * 取得 id，就會踩回 identity map 陷阱——鎖到了資料庫的列，但 Java 物件仍是先前那個
 * 未鎖定讀到的舊值。改用 requireCurrentUserId()（只回傳 accountId，不載入 Employee entity），
 * Service 內以 findByAccountIdForUpdate() 一次鎖定，避免這個問題。
 */
@Service
@Transactional(readOnly = true)
public class EmployeeProfileService {

    private final EmployeeRepository employees;
    private final TrainerRepository trainers;

    public EmployeeProfileService(EmployeeRepository employees, TrainerRepository trainers) {
        this.employees = employees;
        this.trainers = trainers;
    }

    public EmployeeProfileResponse getMyProfile(Integer employeeId) {
        Employee employee = employees.findById(employeeId)
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的員工資料"));
        return EmployeeProfileResponse.from(employee, isTrainer(employee.getEmployeeId()));
    }

    @Transactional
    public EmployeeProfileResponse updateMyProfile(Integer accountId, EmployeeProfileUpdateRequest request) {
        Employee employee = employees.findByAccountIdForUpdate(accountId)
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的員工資料"));

        employee.setPhone(request.getPhone());

        return EmployeeProfileResponse.from(employee, isTrainer(employee.getEmployeeId()));
    }

    private boolean isTrainer(Integer employeeId) {
        return trainers.findByEmployeeEmployeeId(employeeId).filter(t -> t.getDeletedAt() == null).isPresent();
    }
}
