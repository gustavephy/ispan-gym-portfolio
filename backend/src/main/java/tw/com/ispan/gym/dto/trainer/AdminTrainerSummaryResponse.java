package tw.com.ispan.gym.dto.trainer;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/** ADMIN 教練列表用，刻意精簡，比照 AdminEmployeeSummaryResponse 的風格。 */
public record AdminTrainerSummaryResponse(
        Integer trainerId,
        Integer employeeId,
        String displayName,
        String email,
        String specialty,
        TrainerStatus status,
        LocalDateTime deletedAt,
        LocalDateTime createdAt) {

    public static AdminTrainerSummaryResponse from(Trainer trainer) {
        String displayName = trainer.getEmployee().getLastName() + trainer.getEmployee().getFirstName();
        return new AdminTrainerSummaryResponse(
                trainer.getTrainerId(),
                trainer.getEmployee().getEmployeeId(),
                displayName,
                trainer.getEmployee().getAccount().getEmail(),
                trainer.getSpecialty(),
                trainer.getStatus(),
                trainer.getDeletedAt(),
                trainer.getCreatedAt());
    }
}
