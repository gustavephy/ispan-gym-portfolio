package tw.com.ispan.gym.service.coach;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coach.CoachSlotResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.coach.CreateCoachSlotRequest;
import tw.com.ispan.gym.dto.coach.UpdateCoachSlotRequest;
import tw.com.ispan.gym.entity.coach.CoachBooking;
import tw.com.ispan.gym.entity.coach.CoachSlot;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.SlotStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.CoachBookingRepository;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.repository.employee.EmployeeRepository;
import tw.com.ispan.gym.repository.trainer.TrainerRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 教練排班（私教課時段）的商業邏輯，涵蓋建立／查詢／修改／取消四個功能，
 * 對應規格書「教練排班」模組，必須避免時段重疊、非本人修改。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachSlotService {

        private final CoachSlotRepository coachSlotRepository;
        private final TrainerRepository trainerRepository;
        private final EmployeeRepository employeeRepository;
        private final CoachBookingRepository coachBookingRepository;
        private final TrainerService trainerService;

        /**
         * 由登入者的帳號 id 解析出教練資料（account → employee → trainer 兩段查詢）。
         */
        public Trainer requireTrainerByAccount(Integer accountId) {
                return employeeRepository.findByAccountId(accountId)
                                .flatMap(employee -> trainerRepository.findByEmployeeEmployeeId(employee.getEmployeeId()))
                                .orElseThrow(() -> new ForbiddenAccessException("目前帳號沒有教練身分"));
        }

        /**
         * 修改／取消共用的檢查。
         */
        private void requireAcknowledgementIfBooked(CoachSlot slot, boolean acknowledgeExistingBooking) {
                if (slot.getStatus() == SlotStatus.BOOKED && !acknowledgeExistingBooking) {
                        throw new ConfirmationRequiredException("此時段(id=" + slot.getId() + ")已有會員預約，請確認已與會員溝通後再次送出");
                }
        }

        /**
         * 查詢這個時段目前那筆非取消狀態的預約。
         */
        private CoachBooking findActiveBooking(Integer slotId) {
                return coachBookingRepository.findByCoachSlotIdAndStatusNot(slotId, CoachBookingStatus.CANCELLED)
                                .orElse(null);
        }

        /**
         * 建立新的可預約時段。
         */
        @Transactional
        public CoachSlotResponse createSlot(Integer accountId, CreateCoachSlotRequest request) {
                Integer employeeId = employeeRepository.findByAccountId(accountId)
                                .orElseThrow(() -> new ForbiddenAccessException("目前帳號沒有教練身分"))
                                .getEmployeeId();
                Integer trainerId = trainerRepository.findIdByEmployeeId(employeeId)
                                .orElseThrow(() -> new ForbiddenAccessException("目前帳號沒有教練身分"));
                Trainer trainer = trainerService.lockAndAssertAvailableForNewBusiness(trainerId);

                // 查詢重疊
                List<CoachSlot> existing = coachSlotRepository.findOverlapping(trainer.getTrainerId(),
                                request.getStartAt(), request.getEndAt());

                if (!existing.isEmpty()) {
                        throw new BusinessRuleException("已有重疊時段");
                }

                CoachSlot coachSlot = new CoachSlot();
                coachSlot.setTrainer(trainer);
                coachSlot.setStartAt(request.getStartAt());
                coachSlot.setEndAt(request.getEndAt());
                coachSlot.setStatus(SlotStatus.AVAILABLE);
                CoachSlot saveSlot = coachSlotRepository.save(coachSlot);

                log.info("教練{}，新增時段, slotId={},{}~{}", trainer.getTrainerId(), saveSlot.getId(),
                                request.getStartAt(), request.getEndAt());

                CoachBooking activeBooking = findActiveBooking(saveSlot.getId());
                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus(),
                                activeBooking != null ? activeBooking.getId() : null,
                                activeBooking != null ? activeBooking.getStatus() : null);

                return response;
        }

        /**
         * 取消（軟刪除）一筆時段。
         */
        @Transactional
        public CoachSlotResponse cancelSlot(Integer accountId, Integer slotId,
                        boolean acknowledgeExistingBooking) {
                Trainer trainer = requireTrainerByAccount(accountId);
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));

                if (!coachSlot.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        throw new ForbiddenAccessException("無權存取此教練時段：" + slotId);
                }

                requireAcknowledgementIfBooked(coachSlot, acknowledgeExistingBooking);
                coachSlot.setStatus(SlotStatus.CANCELLED);
                CoachSlot saveSlot = coachSlotRepository.save(coachSlot);

                log.info("教練{}，取消時段, slotId={},{}~{}", trainer.getTrainerId(), saveSlot.getId(),
                                coachSlot.getStartAt(), coachSlot.getEndAt());

                CoachBooking activeBooking = findActiveBooking(saveSlot.getId());
                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus(),
                                activeBooking != null ? activeBooking.getId() : null,
                                activeBooking != null ? activeBooking.getStatus() : null);

                return response;
        }

        /**
         * 分頁查詢自己的所有時段，唯讀交易。
         */
        @Transactional(readOnly = true)
        public PageResponse<CoachSlotResponse> getMySlots(Integer accountId, int page, int size) {
                Trainer trainer = requireTrainerByAccount(accountId);

                int safePage = Math.max(page, 0);
                int safeSize = Math.min(Math.max(size, 1), 100);
                Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "startAt"));

                Page<CoachSlot> result = coachSlotRepository.findByTrainerId(trainer.getTrainerId(), pageable);
                List<CoachSlotResponse> items = result.getContent().stream().map(slot -> {
                        CoachBooking activeBooking = findActiveBooking(slot.getId());
                        return new CoachSlotResponse(slot.getId(),
                                        slot.getTrainer().getTrainerId(), slot.getStartAt(), slot.getEndAt(), slot.getStatus(),
                                        activeBooking != null ? activeBooking.getId() : null,
                                        activeBooking != null ? activeBooking.getStatus() : null);
                }).toList();

                return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
        }

        /**
         * 修改一筆已存在時段的時間。
         */
        @Transactional
        public CoachSlotResponse updateSlot(Integer accountId, Integer slotId, UpdateCoachSlotRequest request) {
                Trainer trainer = requireTrainerByAccount(accountId);
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));
                if (!coachSlot.getTrainer().getTrainerId().equals(trainer.getTrainerId())) {
                        throw new ForbiddenAccessException("無權存取此教練時段：" + slotId);
                }
                if (coachSlot.getStatus() == SlotStatus.CANCELLED) {
                        throw new BusinessRuleException("此時段已被取消，無法修改，請重新建立新時段");
                }
                requireAcknowledgementIfBooked(coachSlot, request.isAcknowledgeExistingBooking());

                List<CoachSlot> existing = coachSlotRepository.findOverlappingExcluding(trainer.getTrainerId(), slotId,
                                request.getStartAt(), request.getEndAt());

                if (!existing.isEmpty()) {
                        throw new BusinessRuleException("已有重疊時段");
                }
                LocalDateTime oldStartAt = coachSlot.getStartAt();
                LocalDateTime oldEndAt = coachSlot.getEndAt();
                coachSlot.setStartAt(request.getStartAt());
                coachSlot.setEndAt(request.getEndAt());
                CoachSlot saveSlot = coachSlotRepository.save(coachSlot);

                log.info("教練{}，修改時段, slotId={}, {}~{} 改為 {}~{}",
                                trainer.getTrainerId(), saveSlot.getId(), oldStartAt, oldEndAt,
                                saveSlot.getStartAt(), saveSlot.getEndAt());
                CoachBooking activeBooking = findActiveBooking(saveSlot.getId());
                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus(),
                                activeBooking != null ? activeBooking.getId() : null,
                                activeBooking != null ? activeBooking.getStatus() : null);

                return response;
        }

        /**
         * 把指定時段標記為已被預約。
         */
        @Transactional
        public void markAsBooked(Integer slotId) {
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));

                if (coachSlot.getStatus() != SlotStatus.AVAILABLE) {
                        throw new BusinessRuleException("此時段目前無法預約");
                }

                coachSlot.setStatus(SlotStatus.BOOKED);
                coachSlotRepository.save(coachSlot);
                log.info("時段標記為已預約, slotId={}, trainer={}, {}~{}",
                                coachSlot.getId(), coachSlot.getTrainer().getTrainerId(),
                                coachSlot.getStartAt(), coachSlot.getEndAt());
        }

        /**
         * 把指定時段從 BOOKED 改回 AVAILABLE。
         */
        @Transactional
        public void markAsAvailable(Integer slotId) {
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));

                if (coachSlot.getStatus() == SlotStatus.BOOKED) {
                        coachSlot.setStatus(SlotStatus.AVAILABLE);
                        coachSlotRepository.save(coachSlot);
                        log.info("時段標記為可預約, slotId={}, trainer={}, {}~{}",
                                        coachSlot.getId(), coachSlot.getTrainer().getTrainerId(),
                                        coachSlot.getStartAt(), coachSlot.getEndAt());
                }
        }

        /**
         * 查詢某教練目前所有可預約的時段。
         */
        @Transactional(readOnly = true)
        public List<CoachSlotResponse> searchSlots(Integer trainerId) {
                List<CoachSlot> availableSlots = coachSlotRepository
                                .findByTrainerIdAndStatusOrderByStartAtAsc(trainerId, SlotStatus.AVAILABLE);
                List<CoachSlotResponse> response = availableSlots.stream()
                                .map(slot -> new CoachSlotResponse(slot.getId(),
                                                slot.getTrainer().getTrainerId(), slot.getStartAt(), slot.getEndAt(),
                                                slot.getStatus(), null, null))
                                .toList();

                return response;
        }
}
