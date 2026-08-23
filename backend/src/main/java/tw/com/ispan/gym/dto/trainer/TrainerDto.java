package tw.com.ispan.gym.dto.trainer;

import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;

/**
 * 教練資訊 DTO。
 *
 * 用於前台「教練團隊介紹」、課程細節中的教練資訊展示，以及後台教練管理列表。
 * 姓名與 email 來自 trainer → employee → account 關聯（trainer 表本身只存專業資料，
 * 不重複存個資）；呼叫端的查詢應以 fetch-join 帶出 employee 與 account，避免 N+1。
 */
public record TrainerDto(
        Integer id,
        String name, // lastName + firstName 組合
        String email,
        String specialty, // 教練專長
        String bio, // 個人簡介
        String experience, // 年資經歷
        String avatarUrl // 教練頭像 URL；employee 目前無頭像欄位，暫為 null
) {
    public static TrainerDto from(Trainer trainer) {
        if (trainer == null) {
            return null;
        }
        Employee employee = trainer.getEmployee();
        return new TrainerDto(
                trainer.getTrainerId(),
                employee.getLastName() + employee.getFirstName(),
                employee.getAccount().getEmail(),
                trainer.getSpecialty(),
                trainer.getBio(),
                trainer.getExperience(),
                null);
    }
}
