package tw.com.ispan.gym.dto.trainer;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/**
 * 教練本人查詢／修改專業資料用（GET／PUT /api/trainer/profile）。
 * 一般個資（姓名／電話等）沿用既有 EmployeeProfileResponse，這裡只管 Trainer 專業資料。
 * 不含 createdAt／updatedAt，比照 EmployeeProfileResponse 省略稽核欄位的既有風格。
 */
public record TrainerProfileResponse(
        Integer trainerId,
        Integer employeeId,
        String specialty,
        String experience,
        String bio,
        String profileImageUrl,
        TrainerStatus status) {

    public static TrainerProfileResponse from(Trainer trainer) {
        return new TrainerProfileResponse(
                trainer.getId(),
                trainer.getEmployee().getId(),
                trainer.getSpecialty(),
                trainer.getExperience(),
                trainer.getBio(),
                trainer.getProfileImageUrl(),
                trainer.getStatus());
    }
}
