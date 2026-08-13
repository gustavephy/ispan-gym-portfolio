package tw.com.ispan.gym.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.dto.coachslot.CreateCoachSlotRequest;
import tw.com.ispan.gym.dto.coachslot.UpdateCoachSlotRequest;
import tw.com.ispan.gym.entity.CoachSlot;
import tw.com.ispan.gym.entity.Trainer;
import tw.com.ispan.gym.enums.SlotStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.CoachSlotRepository;
import tw.com.ispan.gym.repository.TrainerRepository;

/**
 * 教練排班（私教課時段）的商業邏輯，涵蓋建立／查詢／修改／取消四個功能，
 * 對應規格書「教練排班」模組，必須避免時段重疊、非本人修改。
 *
 * <p>身分鏈：Controller 從 {@code CurrentUserProvider} 取得的是登入者的帳號 id
 * （Account／Employee 以 @MapsId 共享主鍵，兩者同值），本類別再以
 * {@link TrainerRepository#findByEmployeeId} 解析出對應的 {@link Trainer}；
 * 帳號有 TRAINER 角色但查不到 trainer 資料時視為無教練身分，拒絕操作。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachSlotService {

        private final CoachSlotRepository coachSlotRepository;
        private final TrainerRepository trainerRepository;

        /** 由登入者的帳號 id（= employee id，@MapsId 共享主鍵）解析出教練資料 */
        private Trainer requireTrainerByAccount(Integer accountId) {
                return trainerRepository.findByEmployeeId(accountId)
                                .orElseThrow(() -> new ForbiddenAccessException("目前帳號沒有教練身分"));
        }

        /**
         * 修改／取消共用的檢查：如果時段已被會員預約（BOOKED），
         * 呼叫端必須明確帶 acknowledgeExistingBooking=true 才放行，
         * 否則丟 409，讓前端跳確認視窗、教練確認已跟會員溝通後再重送一次。
         * 抽成共用方法是因為 updateSlot／cancelSlot 需要一模一樣的規則，避免兩處各寫一份。
         */
        private void requireAcknowledgementIfBooked(CoachSlot slot, boolean acknowledgeExistingBooking) {
                if (slot.getStatus() == SlotStatus.BOOKED && !acknowledgeExistingBooking) {
                        throw new ConfirmationRequiredException("此時段(id=" + slot.getId() + ")已有會員預約，請確認已與會員溝通後再次送出");
                }
        }

        /**
         * 建立新的可預約時段。
         * 順序：先解析教練身分，再查重疊（避免建立後才發現衝突、白白產生一筆要清的髒資料），
         * 通過才真的建立、存檔。
         */
        @Transactional
        public CoachSlotResponse createSlot(Integer accountId, CreateCoachSlotRequest request) {
                Trainer trainer = requireTrainerByAccount(accountId);

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

                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus());

                return response;
        }

        /**
         * 取消（軟刪除）一筆時段：把 status 改成 CANCELLED，資料不會真的被刪除。
         * 已是 CANCELLED 的時段可以重複取消（冪等，不會報錯）——
         * 因為 {@link #requireAcknowledgementIfBooked} 只檢查 BOOKED 狀態，CANCELLED 不會被擋。
         * 已被預約（BOOKED）的時段一樣可以取消，但要求呼叫端明確確認（見 acknowledgeExistingBooking）。
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

                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus());

                return response;
        }

        /**
         * 查詢自己的所有時段，唯讀交易。
         * 回傳內容包含 CANCELLED 的舊紀錄，不在這一層過濾，交由前端決定顯示方式。
         */
        @Transactional(readOnly = true)
        public List<CoachSlotResponse> getMySlots(Integer accountId) {
                Trainer trainer = requireTrainerByAccount(accountId);
                List<CoachSlot> mySlot = coachSlotRepository.findByTrainerIdOrderByStartAtAsc(trainer.getTrainerId());
                List<CoachSlotResponse> response = mySlot.stream().map(slot -> new CoachSlotResponse(slot.getId(),
                                slot.getTrainer().getTrainerId(), slot.getStartAt(), slot.getEndAt(), slot.getStatus()))
                                .toList();

                return response;
        }

        /**
         * 修改一筆已存在時段的時間，檢查順序：
         * 查無資料 → 404；非本人 → 403；已是 CANCELLED（終結狀態，不能復活，只能重新建立）→ 409；
         * 已 BOOKED 但未確認 → 409（{@link #requireAcknowledgementIfBooked}）；
         * 新時間跟其他時段重疊 → 409。全部通過才真的更新時間。
         * 重疊檢查用 findOverlappingExcluding，排除自己原本的時間，避免跟自己比對出假衝突。
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
                CoachSlotResponse response = new CoachSlotResponse(saveSlot.getId(),
                                trainer.getTrainerId(),
                                saveSlot.getStartAt(),
                                saveSlot.getEndAt(),
                                saveSlot.getStatus());

                return response;
        }

        /**
         * 把指定時段標記為已被預約，供 {@code CoachBookingService} 建立預約時呼叫，
         * 不對外開放（不會被 Controller 直接呼叫），所以不接身分、不做本人檢查——
         * 呼叫端是系統內部的預約流程，不是教練本人在操作自己的時段。
         *
         * 只允許從 {@link SlotStatus#AVAILABLE} 轉換，已是 BOOKED 或 CANCELLED 都會拒絕：
         * 擋 BOOKED 是防止同一時段被標記兩次（兩人搶同一時段的最後一道防線，
         * 前面還有 {@code CoachSlot.version} 樂觀鎖與資料庫的過濾式唯一索引兩層保護）；
         * 擋 CANCELLED 是單純的業務邏輯——已取消的時段不該讓任何人預約到。
         *
         * 「誰預約了這個時段」不是這個方法的職責，那筆事實記錄在 {@code CoachBooking.member}，
         * 由呼叫端（{@code CoachBookingService}）負責，這裡刻意不知道呼叫者是誰。
         *
         * 回傳 void，不是 CoachSlotResponse：呼叫端只需要知道「有沒有標記成功」，
         * 不像 Controller 直接呼叫的方法需要組一份回應給前端。
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
         * 把指定時段從 BOOKED 改回 AVAILABLE，供 {@code CoachBookingService} 取消預約時呼叫。
         *
         * <p>
         * 只有目前狀態是 BOOKED 才會真的轉換、存檔、記錄 log；若時段已經是 CANCELLED
         * （例如教練自己已先取消這個時段，會員才慢一步來取消預約——這是目前已知、
         * 尚未完全解決的資料一致性缺口，見團隊討論記錄），則安靜地什麼都不做，
         * 不拋例外、也不誤將已取消的時段復活成可預約狀態。
         *
         * <p>
         * 刻意不像 {@link #markAsBooked} 那樣「狀態不對就報錯」，
         * 是因為呼叫端（取消流程）此時已經完成額度退還/消耗、預約狀態變更，
         * 若這裡拋錯會讓 {@code @Transactional} 整個回滾，使會員自己的取消動作也連帶失敗，
         * 對使用者是不合理的連鎖失敗。
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
         * 查詢某教練目前所有可預約（AVAILABLE）的時段，供會員瀏覽、決定要約哪一個。
         * 與 {@link #getMySlots} 不同：這裡的參數是真正的 trainerId（路徑變數指定的任意教練），
         * 不是「目前登入者自己」，因此不做本人身分檢查。
         */
        @Transactional(readOnly = true)
        public List<CoachSlotResponse> searchSlots(Integer trainerId) {
                List<CoachSlot> availableSlots = coachSlotRepository
                                .findByTrainerIdAndStatusOrderByStartAtAsc(trainerId, SlotStatus.AVAILABLE);
                List<CoachSlotResponse> response = availableSlots.stream()
                                .map(slot -> new CoachSlotResponse(slot.getId(),
                                                slot.getTrainer().getTrainerId(), slot.getStartAt(), slot.getEndAt(),
                                                slot.getStatus()))
                                .toList();

                return response;
        }
}
