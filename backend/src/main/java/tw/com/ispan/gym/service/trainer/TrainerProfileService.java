package tw.com.ispan.gym.service.trainer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.trainer.TrainerProfileResponse;
import tw.com.ispan.gym.dto.trainer.TrainerProfileUpdateRequest;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 教練本人查詢／修改專業資料。一般個資（姓名／電話）不在這裡，改走
 * EmployeeProfileService；Email／密碼改走帳號安全流程，兩者皆不接受本 Service 處理。
 *
 * <p>GET／PUT 都掛 hasRole('TRAINER')（見 TrainerProfileController）——這個權限本身就是
 * AccountAuthorityResolver 算出「員工 ACTIVE 且有對應未軟刪除的 Trainer 資料列」才會授予，
 * 所以能通過安全層就已經保證資格；這裡仍各自防禦性再檢查一次，不完全依賴安全層那一瞬間的
 * 判斷，比照 EmployeeProfileService 既有風格。
 *
 * <p>PUT 傳 accountId（Controller 用 requireCurrentUserId()，不透過 requireCurrentEmployeeId()
 * 預先載入 Employee entity）——理由同 EmployeeProfileService：避免先用未鎖定查詢讀到
 * Employee，之後即使再對 Trainer 上鎖，Java 物件仍可能是先前讀到的舊版本（identity map
 * 陷阱）。這裡改鎖的是 Trainer（跟先前讀的 Employee 是不同實體），故先解出 employeeId
 * 不會踩到這個陷阱。
 */
@Service
@Transactional(readOnly = true)
public class TrainerProfileService {

    private final TrainerRepository trainers;
    private final EmployeeRepository employees;

    public TrainerProfileService(TrainerRepository trainers, EmployeeRepository employees) {
        this.trainers = trainers;
        this.employees = employees;
    }

    public TrainerProfileResponse getMyProfile(Integer employeeId) {
        Trainer trainer = trainers.findByEmployeeEmployeeId(employeeId)
                .filter(TrainerProfileService::isUsable)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的教練資料"));
        return TrainerProfileResponse.from(trainer);
    }

    @Transactional
    public TrainerProfileResponse updateMyProfile(Integer accountId, TrainerProfileUpdateRequest request) {
        Integer employeeId = employees.findByAccountId(accountId)
                .map(Employee::getEmployeeId)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的教練資料"));
        Trainer trainer = trainers.findByEmployeeIdForUpdate(employeeId)
                .filter(TrainerProfileService::isUsable)
                .orElseThrow(() -> new ForbiddenAccessException("此帳號沒有可用的教練資料"));

        trainer.setSpecialty(request.getSpecialty().trim());
        trainer.setExperience(request.getExperience().trim());
        trainer.setBio(request.getBio().trim());
        trainer.setProfileImageUrl(normalizeProfileImageUrl(request.getProfileImageUrl()));

        return TrainerProfileResponse.from(trainer);
    }

    private static boolean isUsable(Trainer trainer) {
        Employee employee = trainer.getEmployee();
        return trainer.getDeletedAt() == null
                && employee.getDeletedAt() == null
                && employee.getStatus() == EmployeeStatus.ACTIVE;
    }

    private static String normalizeProfileImageUrl(String profileImageUrl) {
        return (profileImageUrl == null || profileImageUrl.isBlank()) ? null : profileImageUrl.trim();
    }
}
