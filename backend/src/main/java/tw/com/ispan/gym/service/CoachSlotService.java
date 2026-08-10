package tw.com.ispan.gym.service;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.dto.coachslot.CreateCoachSlotRequest;
import tw.com.ispan.gym.entity.CoachSlot;
import tw.com.ispan.gym.enums.SlotStatus;
import tw.com.ispan.gym.exception.ForbiddenSlotAccessException;
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.common.exception.BusinessRuleException;
import tw.com.ispan.gym.common.exception.ResourceNotFoundException;
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

                // 查詢重疊
                List<CoachSlot> existing = coachSlotRepository.findOverlapping(trainerId, request.getStartAt(),
                                request.getEndAt());

                if (!existing.isEmpty()) {
                        throw new BusinessRuleException("已有重疊時段");
                }

                User trainer = userRepository.findById(trainerId)
                                .orElseThrow(() -> new ResourceNotFoundException("教練不存在"));

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

        @Transactional
        public CoachSlotResponse cancelSlot(Integer trainerId, Integer slotId) {
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));

                if (!coachSlot.getTrainer().getId().equals(trainerId)) {
                        throw new ForbiddenSlotAccessException(slotId);
                }

                coachSlot.setStatus(SlotStatus.CANCELLED);
                CoachSlot saveSlot = coachSlotRepository.save(coachSlot);

                log.info("教練{}，取消時段, slotId={},{}~{}", trainerId, saveSlot.getId(), coachSlot.getStartAt(),
                                coachSlot.getEndAt());

                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainerId,
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus());

                return response;
        }

        @Transactional(readOnly = true)
        public List<CoachSlotResponse> getMySlots(Integer trainerId) {
                List<CoachSlot> mySlot = coachSlotRepository.findByTrainerIdOrderByStartAtAsc(trainerId);
                List<CoachSlotResponse> response = mySlot.stream().map(slot -> new CoachSlotResponse(slot.getId(),
                                slot.getTrainer().getId(), slot.getStartAt(), slot.getEndAt(), slot.getStatus()))
                                .toList();

                return response;
        }
}