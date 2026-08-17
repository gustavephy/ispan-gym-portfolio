package tw.com.ispan.gym.service.trainer;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * Trainer 建立/查詢 MVP：只有 ADMIN 能把 ACTIVE 的 Employee 升級為 Trainer，
 * 不走同儕審核（跟 EmployeeReviewService 是不同動作的不同權限模型）。
 * 一位 Employee 一生只會有一筆 Trainer（revive-not-recreate，DB 有 unconditional unique
 * constraint 兜底），MVP 階段還沒有恢復 API，已軟刪除的舊列也一律擋掉重複建立。
 */
@Service
@Transactional(readOnly = true)
public class TrainerService {

    private final TrainerRepository trainers;
    private final EmployeeRepository employees;

    public TrainerService(TrainerRepository trainers, EmployeeRepository employees) {
        this.trainers = trainers;
        this.employees = employees;
    }

    @Transactional
    public TrainerResponse create(Integer employeeId, TrainerCreateRequest request) {
        // 鎖住 Employee 列，讓同一個 employeeId 的併發建立請求序列化，
        // 第二個請求會等第一個 commit 後才往下查，讀到真正已存在的 Trainer，
        // 才能回報精準的「已經是教練」而不是靠 DB unique constraint 兜底的通用訊息
        Employee employee = employees.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此員工"));

        if (employee.getStatus() != EmployeeStatus.ACTIVE || employee.getDeletedAt() != null) {
            throw new BusinessRuleException("此員工目前不是在職狀態，無法建立教練身分");
        }

        Optional<Trainer> existing = trainers.findByEmployeeId(employeeId);
        if (existing.isPresent()) {
            if (existing.get().getDeletedAt() == null) {
                throw new BusinessRuleException("此員工已經是教練");
            }
            throw new BusinessRuleException("此員工先前曾是教練，需走恢復流程，MVP 階段尚未支援");
        }

        Trainer trainer = new Trainer();
        trainer.setEmployee(employee);
        trainer.setSpecialty(request.getSpecialty());
        trainer.setExperience(request.getExperience());
        trainer.setBio(request.getBio());
        trainer.setStatus(TrainerStatus.ACTIVE);

        return TrainerResponse.from(trainers.save(trainer));
    }

    /** 公開列表：ACTIVE／UNAVAILABLE 皆回傳，只排除軟刪除——不叫 listAvailable，避免讓人誤以為只回傳可預約的教練 */
    public List<TrainerResponse> listPublic() {
        return trainers.findByDeletedAtIsNull().stream()
                .map(TrainerResponse::from)
                .toList();
    }

    public TrainerResponse getById(Integer trainerId) {
        Trainer trainer = trainers.findById(trainerId)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("找不到此教練"));
        return TrainerResponse.from(trainer);
    }
}
