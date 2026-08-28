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
    private final tw.com.ispan.gym.repository.member.MemberRepository members;

    public EmployeeProfileService(EmployeeRepository employees, TrainerRepository trainers, tw.com.ispan.gym.repository.member.MemberRepository members) {
        this.employees = employees;
        this.trainers = trainers;
        this.members = members;
    }

    public EmployeeProfileResponse getMyProfile(Integer employeeId) {
        Employee employee = employees.findById(employeeId)
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的員工資料"));
        
        tw.com.ispan.gym.entity.trainer.Trainer trainer = trainers.findByEmployeeId(employee.getId())
                .filter(t -> t.getDeletedAt() == null)
                .orElse(null);
        return EmployeeProfileResponse.from(employee, trainer);
    }

    @Transactional
    public EmployeeProfileResponse updateMyProfile(Integer accountId, EmployeeProfileUpdateRequest request) {
        Employee employee = employees.findByAccountIdForUpdate(accountId)
                .filter(e -> e.getDeletedAt() == null && e.getStatus() == EmployeeStatus.ACTIVE)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的員工資料"));

        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setPhone(request.getPhone());

        // 同步姓名及其他教練資料到 trainer 表（若具有教練身分）
        tw.com.ispan.gym.entity.trainer.Trainer trainer = trainers.findByEmployeeId(employee.getId())
                .filter(t -> t.getDeletedAt() == null)
                .orElse(null);

        if (trainer != null) {
            trainer.setFirstName(employee.getFirstName());
            trainer.setLastName(employee.getLastName());
            
            // 允許教練修改頭像與自我介紹等資訊
            if (request.getProfileImageUrl() != null) {
                trainer.setProfileImageUrl(request.getProfileImageUrl());
            }
            if (request.getSpecialty() != null) {
                trainer.setSpecialty(request.getSpecialty());
            }
            if (request.getExperience() != null) {
                trainer.setExperience(request.getExperience());
            }
            if (request.getBio() != null) {
                trainer.setBio(request.getBio());
            }
            trainers.save(trainer);
        }

        // 同步姓名到會員 (Member) 資料表
        members.findByAccountId(accountId).ifPresent(member -> {
            member.setFirstName(employee.getFirstName());
            member.setLastName(employee.getLastName());
            // 同步電話（長度 10 碼限制）
            if (employee.getPhone() != null && employee.getPhone().length() == 10) {
                member.setPhone(employee.getPhone());
            }
            members.save(member);
        });

        return EmployeeProfileResponse.from(employee, trainer);
    }

}
