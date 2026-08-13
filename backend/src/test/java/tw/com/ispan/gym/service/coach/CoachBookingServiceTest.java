package tw.com.ispan.gym.service.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tw.com.ispan.gym.dto.coach.CoachBookingResponse;
import tw.com.ispan.gym.entity.coach.CoachBooking;
import tw.com.ispan.gym.entity.coach.CoachSlot;
import tw.com.ispan.gym.entity.coach.MemberCourseQuota;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.enums.coach.SlotStatus;
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
 * 純 Mockito 單元測試（沿用 InbodyServiceTest 的做法：專案無內嵌測試資料庫，
 * 不用 @SpringBootTest，直接 mock Repository 與協作的 CoachSlotService）。
 *
 * 涵蓋：預約串接三張表的順序與失敗回滾語意（時段被搶時不建預約、不動額度）、
 * 額度多包時取最早到期、取消預約的 72 小時緩衝（足 72 小時退還／不足視同消耗）
 * 與兩階段確認、本人檢查、PENDING 狀態守衛。
 *
 * 72 小時判斷用的是 service 內部的 LocalDateTime.now()，無法注入 Clock，
 * 測試以「現在 ±30 天／±10 小時」這種大幅度邊界建構時段時間，避免臨界飄移；
 * 若日後把 now 抽成可注入的 Clock，這些測試可改為精準邊界（71:59 vs 72:00）。
 */
class CoachBookingServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer OTHER_MEMBER_ID = 2;
    private static final Integer TRAINER_ID = 5;
    private static final Integer SLOT_ID = 10;
    private static final Integer BOOKING_ID = 99;

    private final CoachSlotRepository slots = mock(CoachSlotRepository.class);
    private final MemberCourseQuotaRepository quotas = mock(MemberCourseQuotaRepository.class);
    private final CoachBookingRepository bookings = mock(CoachBookingRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final CoachSlotService slotService = mock(CoachSlotService.class);
    // 【合併時的修改說明】feat/coach-schedule-entity 給 service 加了稽核紀錄用的
    // SessionDeductionLogRepository 欄位，@RequiredArgsConstructor 生成的建構子因此多一個參數，
    // 這裡跟著補一個 mock；save() 不需要 stub 行為，稽核紀錄寫入成功與否不是這批測試的驗證目標。
    private final SessionDeductionLogRepository deductionLogs = mock(SessionDeductionLogRepository.class);
    private final CoachBookingService service = new CoachBookingService(slots, quotas, bookings, members, slotService,
            deductionLogs);

    // === 共用測試資料 ========================================================

    private Member member(Integer id) {
        Member member = new Member();
        member.setId(id);
        return member;
    }

    private CoachSlot slot(LocalDateTime startAt) {
        Trainer trainer = new Trainer();
        trainer.setTrainerId(TRAINER_ID);
        CoachSlot slot = new CoachSlot();
        slot.setId(SLOT_ID);
        slot.setTrainer(trainer);
        slot.setStartAt(startAt);
        slot.setEndAt(startAt.plusHours(1));
        slot.setStatus(SlotStatus.AVAILABLE);
        return slot;
    }

    private MemberCourseQuota quota(Integer id, int remaining, int reserved) {
        MemberCourseQuota quota = new MemberCourseQuota();
        quota.setId(id);
        quota.setRemainingCourse(remaining);
        quota.setReservedCourse(reserved);
        quota.setUsedCourse(0);
        return quota;
    }

    private CoachBooking booking(Member member, CoachSlot slot, MemberCourseQuota quota, CoachBookingStatus status) {
        CoachBooking booking = new CoachBooking();
        booking.setId(BOOKING_ID);
        booking.setMember(member);
        booking.setCoachSlot(slot);
        booking.setQuota(quota);
        booking.setStatus(status);
        return booking;
    }

    private void stubBookingSaveReturnsArgument() {
        when(bookings.save(any(CoachBooking.class))).thenAnswer(inv -> {
            CoachBooking b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(BOOKING_ID);
            }
            return b;
        });
    }

    // === createBooking ======================================================

    @Test
    void 預約成功_扣額度標記時段並回傳最新剩餘堂數() {
        CoachSlot slot = slot(LocalDateTime.of(2026, 9, 1, 10, 0));
        MemberCourseQuota quota = quota(7, 5, 0);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(quotas.findEligibleQuota(MEMBER_ID, TRAINER_ID)).thenReturn(List.of(quota));
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        stubBookingSaveReturnsArgument();

        CoachBookingResponse response = service.createBooking(MEMBER_ID, SLOT_ID);

        // 額度：remaining-1, reserved+1，恆等式不變
        assertThat(quota.getRemainingCourse()).isEqualTo(4);
        assertThat(quota.getReservedCourse()).isEqualTo(1);
        assertThat(quota.getTotal()).isEqualTo(5);
        // 時段交由 CoachSlotService 標記（狀態守衛在那一層）
        verify(slotService).markAsBooked(SLOT_ID);
        // 預約紀錄
        verify(bookings).save(any(CoachBooking.class));
        assertThat(response.getId()).isEqualTo(BOOKING_ID);
        assertThat(response.getCoachSlotId()).isEqualTo(SLOT_ID);
        assertThat(response.getTrainerId()).isEqualTo(TRAINER_ID);
        assertThat(response.getStatus()).isEqualTo(CoachBookingStatus.PENDING);
        assertThat(response.getRemainingCourse()).isEqualTo(4);
    }

    @Test
    void 預約_查無時段回404() {
        when(slots.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBooking(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(slotService, never()).markAsBooked(anyInt());
    }

    @Test
    void 預約_無可用額度回404且不動時段() {
        CoachSlot slot = slot(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(quotas.findEligibleQuota(MEMBER_ID, TRAINER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createBooking(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("額度");
        verify(slotService, never()).markAsBooked(anyInt());
        verify(bookings, never()).save(any());
    }

    @Test
    void 預約_多包額度時取查詢排序的第一包() {
        CoachSlot slot = slot(LocalDateTime.of(2026, 9, 1, 10, 0));
        MemberCourseQuota earliestExpiry = quota(1, 3, 0);
        MemberCourseQuota laterExpiry = quota(2, 8, 0);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        // findEligibleQuota 依到期日排序，第一包即最早到期
        when(quotas.findEligibleQuota(MEMBER_ID, TRAINER_ID)).thenReturn(List.of(earliestExpiry, laterExpiry));
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        stubBookingSaveReturnsArgument();

        service.createBooking(MEMBER_ID, SLOT_ID);

        assertThat(earliestExpiry.getReservedCourse()).isEqualTo(1);
        assertThat(laterExpiry.getReservedCourse()).isZero();
        verify(quotas).save(earliestExpiry);
    }

    @Test
    void 預約_時段被搶先預約時例外傳播且不扣額度不建預約() {
        CoachSlot slot = slot(LocalDateTime.of(2026, 9, 1, 10, 0));
        MemberCourseQuota quota = quota(7, 5, 0);
        when(slots.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(quotas.findEligibleQuota(MEMBER_ID, TRAINER_ID)).thenReturn(List.of(quota));
        // 兩人搶同一時段，慢的一方在 markAsBooked 的狀態守衛被擋
        doThrow(new BusinessRuleException("此時段目前無法預約")).when(slotService).markAsBooked(SLOT_ID);

        assertThatThrownBy(() -> service.createBooking(MEMBER_ID, SLOT_ID))
                .isInstanceOf(BusinessRuleException.class);

        // markAsBooked 在 quota.book() 之前呼叫：被擋時額度完全未動
        assertThat(quota.getRemainingCourse()).isEqualTo(5);
        assertThat(quota.getReservedCourse()).isZero();
        verify(quotas, never()).save(any());
        verify(bookings, never()).save(any());
    }

    // === cancelBooking ======================================================

    @Test
    void 取消_足72小時前退還堂數並釋放時段() {
        CoachSlot slot = slot(LocalDateTime.now().plusDays(30));
        MemberCourseQuota quota = quota(7, 4, 1);
        CoachBooking booking = booking(member(MEMBER_ID), slot, quota, CoachBookingStatus.PENDING);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        stubBookingSaveReturnsArgument();

        CoachBookingResponse response = service.cancelBooking(MEMBER_ID, BOOKING_ID, false);

        // refund：reserved-1, remaining+1（退還）
        assertThat(quota.getRemainingCourse()).isEqualTo(5);
        assertThat(quota.getReservedCourse()).isZero();
        assertThat(quota.getUsedCourse()).isZero();
        assertThat(booking.getStatus()).isEqualTo(CoachBookingStatus.CANCELLED);
        verify(slotService).markAsAvailable(SLOT_ID);
        assertThat(response.getStatus()).isEqualTo(CoachBookingStatus.CANCELLED);
        assertThat(response.getRemainingCourse()).isEqualTo(5);
    }

    @Test
    void 取消_72小時內未確認回409且不動任何狀態() {
        CoachSlot slot = slot(LocalDateTime.now().plusHours(10));
        MemberCourseQuota quota = quota(7, 4, 1);
        CoachBooking booking = booking(member(MEMBER_ID), slot, quota, CoachBookingStatus.PENDING);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID, false))
                .isInstanceOf(ConfirmationRequiredException.class)
                .hasMessageContaining("扣除堂數");

        assertThat(quota.getRemainingCourse()).isEqualTo(4);
        assertThat(quota.getReservedCourse()).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(CoachBookingStatus.PENDING);
        verify(bookings, never()).save(any());
        verify(slotService, never()).markAsAvailable(anyInt());
    }

    @Test
    void 取消_72小時內確認後視同消耗不退還() {
        CoachSlot slot = slot(LocalDateTime.now().plusHours(10));
        MemberCourseQuota quota = quota(7, 4, 1);
        CoachBooking booking = booking(member(MEMBER_ID), slot, quota, CoachBookingStatus.PENDING);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        stubBookingSaveReturnsArgument();

        CoachBookingResponse response = service.cancelBooking(MEMBER_ID, BOOKING_ID, true);

        // consume：reserved-1, used+1（不退還）
        assertThat(quota.getRemainingCourse()).isEqualTo(4);
        assertThat(quota.getReservedCourse()).isZero();
        assertThat(quota.getUsedCourse()).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(CoachBookingStatus.CANCELLED);
        verify(slotService).markAsAvailable(SLOT_ID);
        assertThat(response.getRemainingCourse()).isEqualTo(4);
    }

    @Test
    void 取消_查無預約回404() {
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 取消_非本人預約拒絕() {
        CoachSlot slot = slot(LocalDateTime.now().plusDays(30));
        MemberCourseQuota quota = quota(7, 4, 0);
        CoachBooking booking = booking(member(OTHER_MEMBER_ID), slot, quota, CoachBookingStatus.PENDING);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID, false))
                .isInstanceOf(ForbiddenAccessException.class);
        assertThat(booking.getStatus()).isEqualTo(CoachBookingStatus.PENDING);
        verify(bookings, never()).save(any());
    }

    @Test
    void 取消_已取消的預約不可再取消() {
        CoachSlot slot = slot(LocalDateTime.now().plusDays(30));
        MemberCourseQuota quota = quota(7, 4, 0);
        CoachBooking booking = booking(member(MEMBER_ID), slot, quota, CoachBookingStatus.CANCELLED);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法取消");
        // 已取消的預約不可重複退堂
        assertThat(quota.getRemainingCourse()).isEqualTo(4);
        verify(bookings, never()).save(any());
    }

    @Test
    void 取消_已核銷的預約不可取消() {
        CoachSlot slot = slot(LocalDateTime.now().plusDays(30));
        MemberCourseQuota quota = quota(7, 4, 0);
        CoachBooking booking = booking(member(MEMBER_ID), slot, quota, CoachBookingStatus.COMPLETED);
        when(bookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID, false))
                .isInstanceOf(BusinessRuleException.class);
        verify(bookings, never()).save(any());
    }
}
