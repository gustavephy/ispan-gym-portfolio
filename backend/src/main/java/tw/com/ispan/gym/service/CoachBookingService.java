package tw.com.ispan.gym.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coachbooking.CoachBookingResponse;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.entity.CoachBooking;
import tw.com.ispan.gym.entity.CoachSlot;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.entity.MemberCourseQuota;
import tw.com.ispan.gym.enums.CoachBookingStatus;
import tw.com.ispan.gym.enums.SlotStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.CoachBookingRepository;
import tw.com.ispan.gym.repository.CoachSlotRepository;
import tw.com.ispan.gym.repository.MemberCourseQuotaRepository;
import tw.com.ispan.gym.repository.MemberRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoachBookingService {

        private final CoachSlotRepository coachSlotRepository;
        private final MemberCourseQuotaRepository memberCourseQuotaRepository;
        private final CoachBookingRepository coachBookingRepository;
        private final MemberRepository memberRepository;
        private final CoachSlotService coachSlotService;

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
                Integer trainerId = coachSlot.getTrainer().getTrainerId();
                List<MemberCourseQuota> eligibleQuota = memberCourseQuotaRepository.findEligibleQuota(memberId,
                                trainerId);
                if (eligibleQuota.isEmpty()) {
                        throw new ResourceNotFoundException("查無可用的教練額度，未購買、已用完或已過期，請確認購買紀錄");
                }
                MemberCourseQuota quota = eligibleQuota.get(0);
                coachSlotService.markAsBooked(slotId);
                quota.book();
                memberCourseQuotaRepository.save(quota);
                Member member = memberRepository.findById(memberId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此會員"));

                CoachBooking coachBooking = new CoachBooking();
                coachBooking.setCoachSlot(coachSlot);
                coachBooking.setMember(member);
                coachBooking.setQuota(quota);
                coachBooking.setStatus(CoachBookingStatus.PENDING);

                CoachBooking saveBooking = coachBookingRepository.save(coachBooking);
                log.info("會員{}建立預約, bookingId={}, slotId={}, quotaId={}",
                                memberId, saveBooking.getId(), slotId, quota.getId());

                CoachBookingResponse response = new CoachBookingResponse(saveBooking.getId(),
                                coachSlot.getId(),
                                trainerId, coachSlot.getStartAt(), coachSlot.getEndAt(), saveBooking.getStatus(),
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
                if (withinLateWindow) {
                        quota.consume();
                } else {
                        quota.refund();
                }
                memberCourseQuotaRepository.save(quota);
                coachBooking.setStatus(CoachBookingStatus.CANCELLED);

                CoachBooking saveBooking = coachBookingRepository.save(coachBooking);
                log.info("會員{}取消預約, bookingId={}, slotId={}, quotaId={}",
                                memberId, bookingId, saveBooking.getCoachSlot().getId(), quota.getId());
                coachSlotService.markAsAvailable(saveBooking.getCoachSlot().getId());

                CoachBookingResponse response = new CoachBookingResponse(bookingId,
                                saveBooking.getCoachSlot().getId(),
                                saveBooking.getCoachSlot().getTrainer().getTrainerId(),
                                saveBooking.getCoachSlot().getStartAt(), saveBooking.getCoachSlot().getEndAt(),
                                saveBooking.getStatus(),
                                quota.getRemainingCourse());
                return response;
        }
}