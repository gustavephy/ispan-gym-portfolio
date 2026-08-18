package tw.com.ispan.gym.service.coach;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.TrainerSummaryResponse;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;

/**
 * 教練名錄的公開查詢，職責單純唯讀：只回傳目前在職（status=ACTIVE 且未軟刪除）的教練清單，
 * 供會員瀏覽用。不涉及任何預約／時段／額度等交易性質的業務規則
 * （那些邏輯已在 CoachSlotService／CoachBookingService），刻意獨立成單一職責的 Service，
 * 不塞進既有的 Service 裡。
 */
@Service
@RequiredArgsConstructor
public class TrainerDirectoryService {

    private final TrainerRepository trainerRepository;

    /** 查詢目前在職的教練清單，姓名由關聯 Employee 的 firstName + lastName 組裝而成。 */
    @Transactional(readOnly = true)
    public List<TrainerSummaryResponse> getActiveTrainers() {
        List<Trainer> trainers = trainerRepository.findByStatusAndDeletedAtIsNull(TrainerStatus.ACTIVE);

        return trainers.stream()
                .map(trainer -> new TrainerSummaryResponse(
                        trainer.getId(),
                        trainer.getEmployee().getFirstName() + trainer.getEmployee().getLastName(),
                        trainer.getSpecialty(),
                        trainer.getExperience()))
                .toList();
    }
}
