package tw.com.ispan.gym.dto.trainer;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/**
 * ADMIN 單筆查詢用，可查已軟刪除的 Trainer（Controller／Service 不過濾 deletedAt）。
 * 額外帶 employeeStatus，方便 ADMIN 判斷底下員工是否仍在職——例如恢復 Trainer 前
 * 需要員工是 ACTIVE，這個欄位讓前端能先行提示而不必另外查 Employee。
 */
public record AdminTrainerResponse(
        Integer trainerId,
        Integer employeeId,
        String displayName,
        String email,
        EmployeeStatus employeeStatus,
        String specialty,
        String experience,
        String bio,
        String profileImageUrl,
        TrainerStatus status,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminTrainerResponse from(Trainer trainer) {
        String displayName = trainer.getEmployee().getLastName() + trainer.getEmployee().getFirstName();
        return new AdminTrainerResponse(
                trainer.getTrainerId(),
                trainer.getEmployee().getEmployeeId(),
                displayName,
                trainer.getEmployee().getAccount().getEmail(),
                trainer.getEmployee().getStatus(),
                trainer.getSpecialty(),
                trainer.getExperience(),
                trainer.getBio(),
                trainer.getProfileImageUrl(),
                trainer.getStatus(),
                trainer.getDeletedAt(),
                trainer.getCreatedAt(),
                trainer.getUpdatedAt());
    }
}
