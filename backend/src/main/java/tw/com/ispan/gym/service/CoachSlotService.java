package tw.com.ispan.gym.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.dto.coachslot.CreateCoachSlotRequest;
import tw.com.ispan.gym.entity.CoachSlot;
import tw.com.ispan.gym.entity.SlotStatus;
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.exception.CustomException;
import tw.com.ispan.gym.repository.CoachSlotRepository;
import tw.com.ispan.gym.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoachSlotService {

    private final CoachSlotRepository coachSlotRepository;
    private final UserRepository userRepository;

    @Transactional
    public CoachSlotResponse createSlot(Integer trainerId, CreateCoachSlotRequest request) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            throw new CustomException(HttpStatus.BAD_REQUEST, "結束時間必須晚於開始時間");
        }
        // 查詢重疊
        List<CoachSlot> existing = coachSlotRepository.findOverlapping(trainerId, request.getStartAt(),
                request.getEndAt());

        if (!existing.isEmpty()) {
            throw new CustomException(HttpStatus.CONFLICT, "已有重疊時段");
        }

        User trainer = userRepository.findById(trainerId)
                .orElseThrow(() -> new CustomException(HttpStatus.INTERNAL_SERVER_ERROR, "教練不存在"));

        CoachSlot coachSlot = new CoachSlot();
        coachSlot.setTrainer(trainer);
        coachSlot.setStartAt(request.getStartAt());
        coachSlot.setEndAt(request.getEndAt());
        coachSlot.setStatus(SlotStatus.AVAILABLE);
        CoachSlot saveSlot = coachSlotRepository.save(coachSlot);

        log.info("教練{}，新增時段, slotId={},{}~{}", trainerId, saveSlot.getId(), request.getStartAt(),
                request.getEndAt());

        CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                trainerId,
                saveSlot.getStartAt(),
                saveSlot.getEndAt(),
                saveSlot.getStatus());

        return response;
    }
}