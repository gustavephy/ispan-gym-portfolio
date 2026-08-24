package tw.com.ispan.gym.dto.trainer;

import tw.com.ispan.gym.entity.trainer.Trainer;

/**
 * 聊天室（§14）選擇對話教練用，僅限登入會員存取（GET /api/member/trainers/chat-directory）。
 * 聊天訊息一律以 employee_id 定址對話對象（見 tw.com.ispan.gym.entity.aihealthchat.ChatMessage
 * 的設計），故這裡刻意夾帶 employeeId——跟公開匿名的 {@link TrainerResponse} 不同，
 * 那個 DTO 不可外洩此內部關聯 FK。
 */
public record TrainerChatDirectoryResponse(
        Integer trainerId,
        Integer employeeId,
        String displayName,
        String specialty) {

    public static TrainerChatDirectoryResponse from(Trainer trainer) {
        String displayName = trainer.getEmployee().getLastName() + trainer.getEmployee().getFirstName();
        return new TrainerChatDirectoryResponse(
                trainer.getTrainerId(),
                trainer.getEmployee().getEmployeeId(),
                displayName,
                trainer.getSpecialty());
    }
}
