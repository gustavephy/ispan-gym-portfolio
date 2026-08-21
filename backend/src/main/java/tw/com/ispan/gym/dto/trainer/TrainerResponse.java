package tw.com.ispan.gym.dto.trainer;

import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/**
 * 公開教練介紹用，匿名可查（GET /api/trainers、GET /api/trainers/{trainerId}）。
 * 只能投影組合後的欄位值，不可夾帶 Employee 內部資料（email/電話/地址/生日/緊急聯絡/角色/審核資訊），
 * 也不可夾帶 employeeId 這類內部關聯 FK——匿名端點不該讓外部得知教練對應的員工編號
 * （2026-08-19 修正：先前曾為了聊天室方便誤把 employeeId 加進本 DTO，
 * 已改走登入限定的 {@link TrainerChatDirectoryResponse} / GET /api/member/trainers/chat-directory）。
 * status 僅代表 ACTIVE/UNAVAILABLE，不代表能不能公開——UNAVAILABLE 教練同樣回傳，由前端自行決定是否停用預約入口。
 */
public record TrainerResponse(
        Integer trainerId,
        String displayName,
        String specialty,
        String experience,
        String bio,
        String profileImageUrl,
        TrainerStatus status) {

    public static TrainerResponse from(Trainer trainer) {
        String displayName = trainer.getEmployee().getLastName() + trainer.getEmployee().getFirstName();
        return new TrainerResponse(
                trainer.getId(),
                displayName,
                trainer.getSpecialty(),
                trainer.getExperience(),
                trainer.getBio(),
                trainer.getProfileImageUrl(),
                trainer.getStatus());
    }
}
