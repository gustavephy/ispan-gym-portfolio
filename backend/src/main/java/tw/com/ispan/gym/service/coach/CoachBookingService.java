package tw.com.ispan.gym.service.coach;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coach.CoachBookingHistoryResponse;
import tw.com.ispan.gym.dto.coach.CoachBookingResponse;
import tw.com.ispan.gym.dto.coach.CoachSlotResponse;
import tw.com.ispan.gym.entity.coach.CoachBooking;
import tw.com.ispan.gym.entity.coach.CoachSlot;
// 【合併時的修改說明】原分支寫的是 tw.com.ispan.gym.entity.Member / entity.Trainer：
// dev 已把實體搬進各自的子套件（entity.member / entity.trainer），這裡跟著改路徑，類別本身沒動。
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.entity.coach.SessionDeductionLog;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.CoachBookingRepository;
import tw.com.ispan.gym.repository.coach.CoachSlotRepository;
import tw.com.ispan.gym.repository.coach.MemberCourseQuotaRepository;
import tw.com.ispan.gym.repository.coach.SessionDeductionLogRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

/**
 * 私教預約（CoachBooking）商業邏輯：包含建立預約、取消、教練核銷與相關稽核紀錄。
 *
 * 重要規則／保證：
 * - 多數方法使用單一 {@code @Transactional}，以確保對 quota、slot、booking 的變更一致性；
 * - 在變更 quota 前會先拍攝 before 值、執行 quota.book()/consume()/refund()、儲存 quota，
 * 再寫入 SessionDeductionLog，確保稽核紀錄的 before/after 是正確對應的；
 * - 建議維持目前的「先存 booking 取得 id 再寫稽核」順序，以便 log 有可關聯的 booking id。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoachBookingService {

        private final CoachSlotRepository coachSlotRepository;
        private final MemberCourseQuotaRepository memberCourseQuotaRepository;
        private final CoachBookingRepository coachBookingRepository;
        private final MemberRepository memberRepository;
        private final CoachSlotService coachSlotService;

        private static final String CHECK_IN_KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 排除 I/O/0/1
        private static final int CHECK_IN_KEY_LENGTH = 8;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final SessionDeductionLogRepository sessionDeductionLogRepository;

        private String generateCheckInKey() {
                StringBuilder stringBuilder = new StringBuilder(CHECK_IN_KEY_LENGTH);
                for (int i = 0; i < CHECK_IN_KEY_LENGTH; i++) {
                        int index = RANDOM.nextInt(CHECK_IN_KEY_CHARS.length());
                        stringBuilder.append(CHECK_IN_KEY_CHARS.charAt(index));
                }
                return stringBuilder.toString();
        }

        /**
         * 寫入一筆銷課稽核紀錄。
         *
         * 呼叫時機規定：一定要放在 quota.book()/consume()/refund() 執行「之後」呼叫。
         * 原因：afterRemaining/afterReserved/afterUsed 三個值是直接讀傳入的 quota 物件
         * 當下的狀態，如果在異動前呼叫，before 跟 after 會變成同一組數字，
         * 稽核紀錄就失去「記錄異動」的意義了。
         *
         * member 沒有另外當參數：MemberCourseQuota 本身就有 member 欄位，直接從 quota 拿，
         * 不用呼叫端多傳一個、也不用多查一次資料庫。
         *
         * delta 沒有讓呼叫端傳：它的定義是「afterRemaining - beforeRemaining」，
         * 方法自己算最不容易出錯——呼叫端如果要自己手動算，多一個地方可能算錯或忘記更新。
         *
         * operatorTrainer 傳 null 代表這是會員自己操作（訂課、自行取消）；
         * 有值代表是教練動作（核銷、或教練取消時段導致的連鎖退還）。
         */
        private void writeDeductionLog(MemberCourseQuota quota, CoachBooking coachBooking, String eventType,
                        int beforeRemaining, int beforeReserved, int beforeUsed,
                        String remark, Trainer operatorTrainer) {

                // after 三個值直接讀 quota 現在的狀態，前提是這個方法在 book()/consume()/refund() 之後才被呼叫
                int afterRemaining = quota.getRemainingCourse();
                int afterReserved = quota.getReservedCourse();
                int afterUsed = quota.getUsedCourse();

                SessionDeductionLog logEntry = new SessionDeductionLog();
                logEntry.setQuota(quota);
                logEntry.setMember(quota.getMember());
                logEntry.setCoachBooking(coachBooking);
                logEntry.setEventType(eventType);
                logEntry.setDelta(afterRemaining - beforeRemaining);
                logEntry.setBeforeRemaining(beforeRemaining);
                logEntry.setAfterRemaining(afterRemaining);
                logEntry.setBeforeReserved(beforeReserved);
                logEntry.setAfterReserved(afterReserved);
                logEntry.setBeforeUsed(beforeUsed);
                logEntry.setAfterUsed(afterUsed);
                logEntry.setRemark(remark);
                logEntry.setOperatorTrainer(operatorTrainer);
                // createdAt 不用手動設定，SessionDeductionLog 有
                // @EntityListeners(AuditingEntityListener.class) + @CreatedDate，
                // save() 的時候 Spring Data JPA 會自動填入（前提是專案已經有 @EnableJpaAuditing，之前的 CoachBooking
                // 系列都靠這個機制，應該已經開著）

                sessionDeductionLogRepository.save(logEntry);
        }

        /**
         * 會員預約教練私教課時段，串接三張表：CoachSlot(時段)、MemberCourseQuota(額度)、CoachBooking(預約紀錄)。
         *
         * 執行順序：查時段 → 依時段找出教練 → 查會員名下該教練的可用額度（見
         * {@link MemberCourseQuotaRepository#findEligibleQuota}，已排除過期與用罄，並依到期日排序）→
         * 額度 book()（remaining-1, reserved+1）→ 呼叫 {@link CoachSlotService#markAsBooked}
         * 把時段標記為 BOOKED → 建立 CoachBooking 存檔。
         *
         * 整個方法用 {@code @Transactional} 包起來：任何一步失敗（例如時段被搶先預約、
         * 額度不足），前面已執行的變更（額度扣減、時段狀態）都會一併回滾，
         * 不會出現「額度扣了但時段沒約成」這種資料不一致的孤兒狀態。
         *
         * trainerId、quotaId 刻意不作為參數，皆由 slotId 查出的資料推導；
         * 呼叫端（Controller）只需提供 memberId（來自 {@code CurrentUserProvider}）與 slotId。
         * 若會員名下有多包同教練的額度，固定挑最早到期的那一包，不支援指定；
         * 若完全無可用額度，回傳統一訊息（不區分「未購買」與「已用罄/過期」）。
         */
        @Transactional
        public CoachBookingResponse createBooking(Integer memberId, Integer slotId) {
                CoachSlot coachSlot = coachSlotRepository.findById(slotId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此時段"));
                Integer trainerId = coachSlot.getTrainer().getId();
                List<MemberCourseQuota> eligibleQuota = memberCourseQuotaRepository.findEligibleQuota(memberId,
                                trainerId);
                if (eligibleQuota.isEmpty()) {
                        throw new ResourceNotFoundException("查無可用的教練額度，未購買、已用完或已過期，請確認購買紀錄");
                }
                MemberCourseQuota quota = eligibleQuota.get(0);
                coachSlotService.markAsBooked(slotId);

                Member member = memberRepository.findById(memberId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此會員"));

                // 【改動①】CoachBooking 提前到 quota.book() 之前建立並存檔，
                // 目的是先拿到 id，稽核紀錄的 coachBooking 外鍵才有東西可指。
                // setQuota(quota) 這裡沒問題：FK 只是「指向這個物件」，跟 quota 的數值有沒有變無關。
                CoachBooking coachBooking = new CoachBooking();
                coachBooking.setCoachSlot(coachSlot);
                coachBooking.setMember(member);
                coachBooking.setQuota(quota);
                coachBooking.setStatus(CoachBookingStatus.PENDING);
                coachBooking.setCheckInKey(generateCheckInKey());
                CoachBooking saveBooking = coachBookingRepository.save(coachBooking);

                // 【改動②】在 quota.book() 真正執行「之前」，先把三個計數器的原始值存起來。
                // 一定要緊貼在 book() 前面拍照，不能提早拍，避免中間如果之後有人加了別的邏輯改到 quota，
                // before 值就會跟實際不符。
                int beforeRemaining = quota.getRemainingCourse();
                int beforeReserved = quota.getReservedCourse();
                int beforeUsed = quota.getUsedCourse();

                quota.book();
                memberCourseQuotaRepository.save(quota);

                // 【改動③】寫稽核紀錄。operatorTrainer 傳 null，因為訂課是會員自己的動作，不是教練操作。
                writeDeductionLog(quota, saveBooking, "BOOK", beforeRemaining, beforeReserved, beforeUsed,
                                "會員訂課", null);

                log.info("會員{}建立預約, bookingId={}, slotId={}, quotaId={}",
                                memberId, saveBooking.getId(), slotId, quota.getId());

                CoachBookingResponse response = new CoachBookingResponse(saveBooking.getId(),
                                coachSlot.getId(),
                                trainerId, coachSlot.getStartAt(), coachSlot.getEndAt(), saveBooking.getStatus(),
                                saveBooking.getCheckInKey(),
                                quota.getRemainingCourse());
                return response;
        }

        /**
         * 會員取消自己的一筆私教課預約，規則是 72 小時緩衝：
         * 距上課時間 ≥72 小時取消 → quota.refund()（退還，remaining+1）；
         * <72 小時取消 → quota.consume()（視同已消耗，不退還），且需先經過兩階段確認
         * （見 acknowledgeConsumption，機制與 CoachSlotService 的已預約時段確認相同，但確認的內容不同）。
         *
         * <p>
         * 檢查順序：查無此預約 → 404；非本人 → 403；狀態不是 PENDING（已取消或已核銷）→ 409
         * （BUSINESS_RULE_VIOLATION，不需確認，因為不管確不確認結果都一樣不能做）；
         * 72 小時內且未確認 → 409（CONFIRMATION_REQUIRED）。全部通過才真的執行退還/消耗、
         * 更新預約狀態、並呼叫 {@link CoachSlotService#markAsAvailable} 釋放時段。
         *
         * <p>
         * quota 直接透過 {@code coachBooking.getQuota()} 取得，不重新查詢——
         * 取消永遠是動這筆預約當初綁定的那一包額度，不會有第二種可能，
         * 因此不能沿用 {@link CoachSlotService} 之類挑選額度的查詢方法。
         */
        @Transactional
        public CoachBookingResponse cancelBooking(Integer memberId, Integer bookingId, boolean acknowledgeConsumption) {

                CoachBooking coachBooking = coachBookingRepository.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此預約"));
                if (!coachBooking.getMember().getId().equals(memberId)) {
                        throw new ForbiddenAccessException("無權操作此預約：" + bookingId);
                }
                if (coachBooking.getStatus() != CoachBookingStatus.PENDING) {
                        throw new BusinessRuleException("此預約目前無法取消");
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDateTime startAt = coachBooking.getCoachSlot().getStartAt();
                Duration duration = Duration.between(now, startAt);
                boolean withinLateWindow = duration.toHours() < 72;
                if (withinLateWindow && !acknowledgeConsumption) {
                        throw new ConfirmationRequiredException("預約時間72小時之內取消，將扣除堂數，請確認");
                }

                MemberCourseQuota quota = coachBooking.getQuota();

                // 【改動①】跟 createBooking 一樣，一定要緊貼在 consume()/refund() 之前拍照 before 值。
                int beforeRemaining = quota.getRemainingCourse();
                int beforeReserved = quota.getReservedCourse();
                int beforeUsed = quota.getUsedCourse();

                // 【改動②】eventType 跟 remark 跟著分支決定，這是這步的重點——
                // 兩個分支各自的 quota 異動方法不同，稽核紀錄當然也要記錄「發生的是哪一種事件」。
                String eventType;
                String remark;
                if (withinLateWindow) {
                        quota.consume();
                        eventType = "CANCEL_CONSUME";
                        remark = "72小時內取消，視同消耗不予退還";
                } else {
                        quota.refund();
                        eventType = "CANCEL_REFUND";
                        remark = "準時取消（≥72小時），退還堂數";
                }
                memberCourseQuotaRepository.save(quota);
                coachBooking.setStatus(CoachBookingStatus.CANCELLED);

                CoachBooking saveBooking = coachBookingRepository.save(coachBooking);

                // 【改動③】寫稽核紀錄。operatorTrainer 一樣傳 null——這是會員自己按取消，不是教練操作。
                writeDeductionLog(quota, saveBooking, eventType, beforeRemaining, beforeReserved, beforeUsed,
                                remark, null);

                log.info("會員{}取消預約, bookingId={}, slotId={}, quotaId={}",
                                memberId, bookingId, saveBooking.getCoachSlot().getId(), quota.getId());
                coachSlotService.markAsAvailable(saveBooking.getCoachSlot().getId());

                CoachBookingResponse response = new CoachBookingResponse(bookingId,
                                saveBooking.getCoachSlot().getId(),
                                saveBooking.getCoachSlot().getTrainer().getId(),
                                saveBooking.getCoachSlot().getStartAt(), saveBooking.getCoachSlot().getEndAt(),
                                saveBooking.getStatus(), saveBooking.getCheckInKey(),
                                quota.getRemainingCourse());
                return response;
        }

        /**
         * 教練核銷會員的私教課預約。呼叫端同時帶 bookingId（路徑）跟 checkInKey（body），
         * bookingId 先鎖定範圍、順便做本人檢查，checkInKey 才是真正的身分核對第二層防護。
         *
         * 檢查順序：查無此預約 → 404；帳號沒有教練身分 → 403（requireTrainerByAccount 本身就會丟）；
         * 這筆時段不是這位教練的 → 403（跟 CoachSlotService.cancelSlot 同一套邏輯，非本人不能操作）；
         * 狀態不是 PENDING → 409（已經核銷過、或已被取消，這就是防重複核銷的關鍵一行）；
         * checkInKey 對不上 → 409（教練輸入錯誤，不是系統錯誤，用 BusinessRuleException 這個 409 家族）。
         * 全部通過才真的執行 consume()、寫核銷時間、寫核銷人、寫稽核紀錄。
         */
        @Transactional
        public CoachBookingResponse checkIn(Integer accountId, Integer bookingId, String checkInKey) {
                Trainer trainer = coachSlotService.requireTrainerByAccount(accountId);

                CoachBooking coachBooking = coachBookingRepository.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此預約"));

                if (!coachBooking.getCoachSlot().getTrainer().getId().equals(trainer.getId())) {
                        throw new ForbiddenAccessException("無權核銷此預約：" + bookingId);
                }

                if (coachBooking.getStatus() != CoachBookingStatus.PENDING) {
                        throw new BusinessRuleException("此預約目前無法核銷（可能已核銷或已取消）");
                }

                // 【合併時的修改說明】原本只有一行 !coachBooking.getCheckInKey().equals(checkInKey)：
                // 核銷功能上線「之前」建立的舊預約，checkInKey 欄位是 null，
                // 原寫法走到這裡會 NPE、落入通用例外處理轉成 500，教練端只會看到「伺服器發生錯誤」。
                // 補上 null 先擋，讓舊預約回 409 與明確訊息；新預約的行為完全不變。
                if (coachBooking.getCheckInKey() == null) {
                        throw new BusinessRuleException("此預約建立於核銷功能上線前，沒有核銷碼，請洽管理人員手動處理");
                }
                if (!coachBooking.getCheckInKey().equals(checkInKey)) {
                        throw new BusinessRuleException("核銷碼錯誤，請確認會員出示的核銷碼");
                }

                MemberCourseQuota quota = coachBooking.getQuota();

                // 跟前面三個方法同一套規矩：緊貼在 consume() 之前拍照 before 值
                int beforeRemaining = quota.getRemainingCourse();
                int beforeReserved = quota.getReservedCourse();
                int beforeUsed = quota.getUsedCourse();

                quota.consume();
                memberCourseQuotaRepository.save(quota);

                coachBooking.setStatus(CoachBookingStatus.COMPLETED);
                coachBooking.setCheckInTime(LocalDateTime.now());
                coachBooking.setCheckInApprover(trainer);

                CoachBooking saveBooking = coachBookingRepository.save(coachBooking);

                // operatorTrainer 傳 trainer 本人——核銷是教練的動作，不是會員自己的動作
                writeDeductionLog(quota, saveBooking, "CHECKIN", beforeRemaining, beforeReserved, beforeUsed,
                                "教練核銷到場上課", trainer);

                log.info("教練{}核銷預約, bookingId={}, memberId={}, quotaId={}",
                                trainer.getId(), bookingId, coachBooking.getMember().getId(), quota.getId());

                CoachBookingResponse response = new CoachBookingResponse(saveBooking.getId(),
                                saveBooking.getCoachSlot().getId(),
                                trainer.getId(),
                                saveBooking.getCoachSlot().getStartAt(), saveBooking.getCoachSlot().getEndAt(),
                                saveBooking.getStatus(), saveBooking.getCheckInKey(),
                                quota.getRemainingCourse());
                return response;
        }

        @Transactional
        public CoachSlotResponse cancelSlotWithCascade(Integer accountId, Integer slotId,
                        boolean acknowledgeExistingBooking) {
                CoachSlotResponse slotResponse = coachSlotService.cancelSlot(accountId, slotId,
                                acknowledgeExistingBooking);

                coachBookingRepository.findByCoachSlotIdAndStatus(slotId, CoachBookingStatus.PENDING)
                                .ifPresent(booking -> {
                                        MemberCourseQuota quota = booking.getQuota();

                                        // 【新增】跟前面兩個方法一樣，緊貼在 refund() 之前拍照
                                        int beforeRemaining = quota.getRemainingCourse();
                                        int beforeReserved = quota.getReservedCourse();
                                        int beforeUsed = quota.getUsedCourse();

                                        quota.refund();
                                        memberCourseQuotaRepository.save(quota);

                                        booking.setStatus(CoachBookingStatus.CANCELLED);
                                        CoachBooking saveBooking = coachBookingRepository.save(booking);

                                        // 【新增】operatorTrainer 這次不是 null——觸發者是教練，不是會員自己，
                                        // 直接從 booking 現有的關聯拿，不用重新查證身分。
                                        Trainer operatorTrainer = saveBooking.getCoachSlot().getTrainer();
                                        writeDeductionLog(quota, saveBooking, "CANCEL_REFUND",
                                                        beforeRemaining, beforeReserved, beforeUsed,
                                                        "教練取消時段導致連鎖退還", operatorTrainer);

                                        log.info("教練取消時段導致預約取消, slotId={}, bookingId={}, memberId={}, quotaId={}",
                                                        slotId, booking.getId(), booking.getMember().getId(),
                                                        quota.getId());
                                });

                return slotResponse;
        }

        /**
         * 查詢會員自己的所有私教課預約紀錄，依建立時間新到舊排序。
         *
         * <p>
         * remainingCourseSnapshot 的來源是 SessionDeductionLog.afterRemaining，取「這筆預約
         * 最新一筆事件」的快照，不是即時查詢 quota 目前的值：還是 PENDING 就是 BOOK 事件當下
         * 的餘額，已 COMPLETED/CANCELLED 就是那個終結事件當下的餘額。同一包額度底下的多筆
         * 預約，這個數字可能各自不同，跟 {@link CoachBookingResponse}（即時餘額）語意不同。
         *
         * <p>
         * 稽核寫入功能上線前建立的舊預約沒有對應的 log，這種情況快照給 null，
         * 前端請處理成「無歷史資料」，不要當成 0 堂看待。
         *
         * <p>
         * 這裡用批次查詢（一次撈出這批 booking 全部的 log，再用 Map 分組）取代
         * 「每筆 booking 各查一次」，避免 N 筆預約打 N 次資料庫的 N+1 問題。
         */
        @Transactional(readOnly = true)
        public List<CoachBookingHistoryResponse> getMyBookings(Integer memberId) {
                List<CoachBooking> myBooking = coachBookingRepository.findByMemberIdOrderByCreatedAtDesc(memberId);

                List<Integer> bookingIds = myBooking.stream().map(booking -> booking.getId()).toList();
                List<SessionDeductionLog> logs = sessionDeductionLogRepository
                                .findByCoachBookingIdInOrderByIdDesc(bookingIds);

                // key = coachBookingId，value = 該預約最新一筆 log。
                // logs 已經照 id 新到舊排序，同一個 key 重複出現時，合併函式選 first（先遇到的、較新的那筆）。
                Map<Integer, SessionDeductionLog> logMap = logs.stream()
                                .collect(Collectors.toMap(
                                                log -> log.getCoachBooking().getId(),
                                                log -> log,
                                                (first, second) -> first));

                List<CoachBookingHistoryResponse> response = myBooking.stream()
                                .map(booking -> {
                                        SessionDeductionLog log = logMap.getOrDefault(booking.getId(), null);
                                        Integer snapshot = (log == null) ? null : log.getAfterRemaining();
                                        return new CoachBookingHistoryResponse(
                                                        booking.getId(),
                                                        booking.getCoachSlot().getId(),
                                                        booking.getCoachSlot().getTrainer().getId(),
                                                        booking.getCoachSlot().getStartAt(),
                                                        booking.getCoachSlot().getEndAt(),
                                                        booking.getStatus(), booking.getCheckInKey(),
                                                        snapshot);
                                })
                                .toList();
                return response;
        }
}