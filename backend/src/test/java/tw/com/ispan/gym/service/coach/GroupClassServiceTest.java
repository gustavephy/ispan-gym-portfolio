package tw.com.ispan.gym.service.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import tw.com.ispan.gym.dto.coach.CreateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.GroupClassBookingResponse;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.dto.coach.UpdateGroupClassSessionRequest;
import tw.com.ispan.gym.entity.coach.GroupClassBooking;
import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.GroupClassBookingRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * 純 Mockito 單元測試，風格比照 CoachBookingServiceTest：
 * 不用 @SpringBootTest，直接 mock 全部 5 個 Repository。
 *
 * 涵蓋 GroupClassService 全部 5 支會異動資料的方法：
 * bookSession/cancelBooking（會員視角）、createSession/updateSession/cancelSession（管理視角）。
 * 兩支唯讀查詢（getUpcomingSessions/getUpcomingSessionsForAdmin）風險低，不在這批範圍。
 *
 * bookSession 案例 7（樂觀鎖衝突）驗證的是「save() 失敗時例外正確往外拋、
 * 不誤建立報名紀錄」，不是真的模擬兩個請求同時到達資料庫——純 mock 測試沒有
 * 真的資料庫，測不到真正的併發競爭，如果之後要更嚴謹地驗證併發，
 * 需要另外寫 @SpringBootTest 搭配真的資料庫做整合測試。
 */
class GroupClassServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final Integer OTHER_MEMBER_ID = 2;
    private static final Integer SESSION_ID = 50;
    private static final Integer BOOKING_ID = 200;
    private static final Integer COURSE_ID = 30;
    private static final Integer TRAINER_ID = 5;

    private final GroupClassSessionRepository sessions = mock(GroupClassSessionRepository.class);
    private final GroupCourseRepository courses = mock(GroupCourseRepository.class);
    private final MemberSubscriptionRepository subscriptions = mock(MemberSubscriptionRepository.class);
    private final GroupClassBookingRepository classBookings = mock(GroupClassBookingRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final TrainerService trainerService = mock(TrainerService.class);
    private final GroupClassService service = new GroupClassService(sessions, courses, subscriptions, classBookings,
            members, trainerService);

    // === 共用測試資料 ========================================================

    private Member member(Integer id) {
        Member member = new Member();
        member.setId(id);
        return member;
    }

    /** 課程需要帶完整的 trainer/employee 關聯，因為 create/update/cancelSession 的
     * response 映射會讀 course.getTrainer().getEmployee().getFirstName()/getLastName()。 */
    private GroupCourse course() {
        GroupCourse course = new GroupCourse();
        course.setGroupCourseId(COURSE_ID);
        course.setName("戰鬥有氧");
        course.setMaxCapacity(15);

        Employee employee = new Employee();
        employee.setFirstName("怡君");
        employee.setLastName("陳");

        Trainer trainer = new Trainer();
        trainer.setTrainerId(TRAINER_ID);
        trainer.setEmployee(employee);

        course.setTrainer(trainer);
        return course;
    }

    private GroupClassSession session(GroupClassSessionStatus status, int capacity, int bookedCount,
            LocalDateTime startAt) {
        GroupClassSession session = new GroupClassSession();
        session.setId(SESSION_ID);
        session.setCourse(course());
        session.setRoomName("A教室");
        session.setStartAt(startAt);
        session.setEndAt(startAt.plusHours(1));
        session.setStatus(status);
        session.setCapacity(capacity);
        session.setBookedCount(bookedCount);
        return session;
    }

    private GroupClassBooking booking(Member member, GroupClassSession session, GroupClassBookingStatus status) {
        GroupClassBooking booking = new GroupClassBooking();
        booking.setId(BOOKING_ID);
        booking.setMember(member);
        booking.setSession(session);
        booking.setStatus(status);
        return booking;
    }

    private UpdateGroupClassSessionRequest updateRequest(String roomName, LocalDateTime startAt,
            LocalDateTime endAt, Integer capacity, Integer version) {
        UpdateGroupClassSessionRequest request = new UpdateGroupClassSessionRequest();
        request.setRoomName(roomName);
        request.setStartAt(startAt);
        request.setEndAt(endAt);
        request.setCapacity(capacity);
        request.setVersion(version);
        return request;
    }

    // save() 對應 IDENTITY 產生的 id：既有 id 就沿用（cancelBooking/updateSession
    // 操作的都是已存在的場次），沒有 id 才補一個（createSession 建立新場次的情境）。
    private void stubSessionSaveReturnsArgument() {
        when(sessions.save(any(GroupClassSession.class))).thenAnswer(inv -> {
            GroupClassSession s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(SESSION_ID);
            }
            return s;
        });
    }

    private void stubSessionSaveAndFlushReturnsArgument() {
        when(sessions.saveAndFlush(any(GroupClassSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubBookingSaveReturnsArgument() {
        when(classBookings.save(any(GroupClassBooking.class))).thenAnswer(inv -> {
            GroupClassBooking b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(BOOKING_ID);
            }
            return b;
        });
    }

    // === bookSession =========================================================

    @Test
    void 報名成功_增加已報名人數並建立報名紀錄() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(new MemberSubscription()));
        when(classBookings.findBySessionIdAndMemberIdAndStatus(SESSION_ID, MEMBER_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(Optional.empty());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        stubSessionSaveReturnsArgument();
        stubBookingSaveReturnsArgument();

        GroupClassBookingResponse response = service.bookSession(MEMBER_ID, SESSION_ID);

        assertThat(session.getBookedCount()).isEqualTo(4);
        verify(sessions).save(session);
        verify(classBookings).save(any(GroupClassBooking.class));
        assertThat(response.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(response.getStatus()).isEqualTo(GroupClassBookingStatus.BOOKED);
    }

    @Test
    void 報名_查無場次回404() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(classBookings, never()).save(any());
    }

    @Test
    void 報名_查無有效訂閱回409且不動報名人數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("訂閱");
        assertThat(session.getBookedCount()).isEqualTo(3);
        verify(sessions, never()).save(any());
        verify(classBookings, never()).save(any());
    }

    @Test
    void 報名_已報名過此場次回409且不重複計數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        GroupClassBooking existing = booking(member(MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(new MemberSubscription()));
        when(classBookings.findBySessionIdAndMemberIdAndStatus(SESSION_ID, MEMBER_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重複報名");
        assertThat(session.getBookedCount()).isEqualTo(3);
        verify(sessions, never()).save(any());
        verify(classBookings, never()).save(any(GroupClassBooking.class));
    }

    @Test
    void 報名_場次已額滿回409且不建立報名紀錄() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 5, 5, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(new MemberSubscription()));
        when(classBookings.findBySessionIdAndMemberIdAndStatus(SESSION_ID, MEMBER_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(Optional.empty());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("額滿");
        assertThat(session.getBookedCount()).isEqualTo(5);
        verify(sessions, never()).save(any());
        verify(classBookings, never()).save(any());
    }

    @Test
    void 報名_場次已取消回409且不建立報名紀錄() {
        GroupClassSession session = session(GroupClassSessionStatus.CANCELLED, 10, 3, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(new MemberSubscription()));
        when(classBookings.findBySessionIdAndMemberIdAndStatus(SESSION_ID, MEMBER_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(Optional.empty());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已取消");
        verify(sessions, never()).save(any());
        verify(classBookings, never()).save(any());
    }

    @Test
    void 報名_儲存場次時樂觀鎖衝突例外往外拋且不建立報名紀錄() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(subscriptions.findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(eq(MEMBER_ID),
                any(LocalDateTime.class), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(new MemberSubscription()));
        when(classBookings.findBySessionIdAndMemberIdAndStatus(SESSION_ID, MEMBER_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(Optional.empty());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member(MEMBER_ID)));
        when(sessions.save(any(GroupClassSession.class)))
                .thenThrow(new OptimisticLockingFailureException("此場次已被其他人異動，請重新整理"));

        assertThatThrownBy(() -> service.bookSession(MEMBER_ID, SESSION_ID))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(classBookings, never()).save(any());
    }

    // === cancelBooking ========================================================

    @Test
    void 取消報名成功_減少已報名人數並標記取消() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 4, LocalDateTime.now().plusDays(3));
        GroupClassBooking booking = booking(member(MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        when(classBookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        stubSessionSaveReturnsArgument();
        stubBookingSaveReturnsArgument();

        GroupClassBookingResponse response = service.cancelBooking(MEMBER_ID, BOOKING_ID);

        assertThat(session.getBookedCount()).isEqualTo(3);
        assertThat(booking.getStatus()).isEqualTo(GroupClassBookingStatus.CANCELLED);
        verify(sessions).save(session);
        verify(classBookings).save(booking);
        assertThat(response.getStatus()).isEqualTo(GroupClassBookingStatus.CANCELLED);
    }

    @Test
    void 取消報名_查無報名紀錄回404() {
        when(classBookings.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).save(any());
    }

    @Test
    void 取消報名_非本人操作回403且不動報名人數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 4, LocalDateTime.now().plusDays(3));
        GroupClassBooking booking = booking(member(OTHER_MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        when(classBookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID))
                .isInstanceOf(ForbiddenAccessException.class);
        assertThat(session.getBookedCount()).isEqualTo(4);
        verify(sessions, never()).save(any());
    }

    @Test
    void 取消報名_狀態已非BOOKED回409且不動報名人數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 4, LocalDateTime.now().plusDays(3));
        GroupClassBooking booking = booking(member(MEMBER_ID), session, GroupClassBookingStatus.CANCELLED);
        when(classBookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法取消");
        assertThat(session.getBookedCount()).isEqualTo(4);
        verify(sessions, never()).save(any());
    }

    @Test
    void 取消報名_場次已開始回409且不動報名人數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 4, LocalDateTime.now().minusHours(1));
        GroupClassBooking booking = booking(member(MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        when(classBookings.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelBooking(MEMBER_ID, BOOKING_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已開始");
        assertThat(session.getBookedCount()).isEqualTo(4);
        verify(sessions, never()).save(any());
    }

    // === createSession ========================================================

    @Test
    void 建立成功_未帶容量時fallback課程maxCapacity() {
        GroupCourse course = course();
        CreateGroupClassSessionRequest request = new CreateGroupClassSessionRequest();
        request.setGroupCourseId(COURSE_ID);
        request.setRoomName("B教室");
        request.setStartAt(LocalDateTime.now().plusDays(5));
        request.setEndAt(LocalDateTime.now().plusDays(5).plusHours(1));

        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        stubSessionSaveReturnsArgument();

        GroupClassSessionResponse response = service.createSession(request);

        assertThat(response.getCapacity()).isEqualTo(15);
        verify(sessions).save(any(GroupClassSession.class));
    }

    @Test
    void 建立成功_有帶容量時使用帶入的值() {
        GroupCourse course = course();
        CreateGroupClassSessionRequest request = new CreateGroupClassSessionRequest();
        request.setGroupCourseId(COURSE_ID);
        request.setRoomName("B教室");
        request.setStartAt(LocalDateTime.now().plusDays(5));
        request.setEndAt(LocalDateTime.now().plusDays(5).plusHours(1));
        request.setCapacity(8);

        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        stubSessionSaveReturnsArgument();

        GroupClassSessionResponse response = service.createSession(request);

        assertThat(response.getCapacity()).isEqualTo(8);
    }

    @Test
    void 建立_查無課程回404且不建立場次() {
        CreateGroupClassSessionRequest request = new CreateGroupClassSessionRequest();
        request.setGroupCourseId(COURSE_ID);
        request.setRoomName("B教室");
        request.setStartAt(LocalDateTime.now().plusDays(5));
        request.setEndAt(LocalDateTime.now().plusDays(5).plusHours(1));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).save(any());
    }

    @Test
    void 建立_教室時段重疊回409且不建立場次() {
        GroupCourse course = course();
        CreateGroupClassSessionRequest request = new CreateGroupClassSessionRequest();
        request.setGroupCourseId(COURSE_ID);
        request.setRoomName("B教室");
        request.setStartAt(LocalDateTime.now().plusDays(5));
        request.setEndAt(LocalDateTime.now().plusDays(5).plusHours(1));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(session(GroupClassSessionStatus.SCHEDULED, 10, 0, LocalDateTime.now().plusDays(5))));

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重疊");
        verify(sessions, never()).save(any());
    }

    @Test
    void 建立_教練非可服務狀態擋下且不建立場次() {
        // §9 跨模組防護：教練 UNAVAILABLE／已軟刪除時，即使課程本身仍是 ACTIVE，
        // 也不可再指派新的團課場次。TrainerService 本身的判斷邏輯由 TrainerServiceTest
        // 驗證，這裡只驗證 GroupClassService 有沒有在建立場次前就先擋下。
        GroupCourse course = course();
        CreateGroupClassSessionRequest request = new CreateGroupClassSessionRequest();
        request.setGroupCourseId(COURSE_ID);
        request.setRoomName("B教室");
        request.setStartAt(LocalDateTime.now().plusDays(5));
        request.setEndAt(LocalDateTime.now().plusDays(5).plusHours(1));
        when(courses.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(trainerService.lockAndAssertAvailableForNewBusiness(TRAINER_ID))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("無法承接新業務");
        verify(sessions, never()).save(any());
    }

    // === updateSession ========================================================

    @Test
    void 更新成功_重疊檢查排除自己() {
        GroupClassSession existing = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        LocalDateTime newStart = LocalDateTime.now().plusDays(6);
        LocalDateTime newEnd = newStart.plusHours(1);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));
        // 重疊查詢只查到自己，updateSession 內部要把自己排除掉才不會誤判衝突
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new ArrayList<>(List.of(existing)));
        stubSessionSaveAndFlushReturnsArgument();

        UpdateGroupClassSessionRequest request = updateRequest("B教室", newStart, newEnd, 12, 2);
        GroupClassSessionResponse response = service.updateSession(SESSION_ID, request);

        assertThat(existing.getRoomName()).isEqualTo("B教室");
        assertThat(existing.getCapacity()).isEqualTo(12);
        assertThat(response.getRoomName()).isEqualTo("B教室");
        verify(sessions).saveAndFlush(existing);
    }

    @Test
    void 更新成功_容量未帶時維持原容量() {
        GroupClassSession existing = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        LocalDateTime newStart = LocalDateTime.now().plusDays(6);
        LocalDateTime newEnd = newStart.plusHours(1);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new ArrayList<>());
        stubSessionSaveAndFlushReturnsArgument();

        UpdateGroupClassSessionRequest request = updateRequest("B教室", newStart, newEnd, null, 2);
        service.updateSession(SESSION_ID, request);

        assertThat(existing.getCapacity()).isEqualTo(10);
    }

    @Test
    void 更新_查無此場次回404() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSession(SESSION_ID, updateRequest("B教室",
                LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(6).plusHours(1), 10, 1)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void 更新_已取消場次不可修改回409() {
        GroupClassSession existing = session(GroupClassSessionStatus.CANCELLED, 10, 0, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateSession(SESSION_ID, updateRequest("B教室",
                LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(6).plusHours(1), 10, 2)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已被取消");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void 更新_版本不符回409() {
        GroupClassSession existing = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateSession(SESSION_ID, updateRequest("B教室",
                LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(6).plusHours(1), 10, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已被其他人修改");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void 更新_教室與其他場次重疊回409() {
        GroupClassSession existing = session(GroupClassSessionStatus.SCHEDULED, 10, 3, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        GroupClassSession other = new GroupClassSession();
        other.setId(SESSION_ID + 1);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new ArrayList<>(List.of(other)));

        assertThatThrownBy(() -> service.updateSession(SESSION_ID, updateRequest("B教室",
                LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(6).plusHours(1), 10, 2)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重疊");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void 更新_容量小於已報名人數回409() {
        GroupClassSession existing = session(GroupClassSessionStatus.SCHEDULED, 10, 8, LocalDateTime.now().plusDays(5));
        existing.setVersion(2);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(existing));
        when(sessions.findOverlappingByRoom(eq("B教室"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new ArrayList<>());

        assertThatThrownBy(() -> service.updateSession(SESSION_ID, updateRequest("B教室",
                LocalDateTime.now().plusDays(6), LocalDateTime.now().plusDays(6).plusHours(1), 5, 2)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不可小於");
        verify(sessions, never()).saveAndFlush(any());
    }

    // === cancelSession ========================================================

    @Test
    void 取消場次成功_無人報名時直接取消() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 0, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(classBookings.findBySessionIdAndStatus(SESSION_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(List.of());
        stubSessionSaveAndFlushReturnsArgument();

        GroupClassSessionResponse response = service.cancelSession(SESSION_ID, false);

        assertThat(session.getStatus()).isEqualTo(GroupClassSessionStatus.CANCELLED);
        assertThat(response.getStatus()).isEqualTo(GroupClassSessionStatus.CANCELLED);
        verify(sessions).saveAndFlush(session);
        verify(classBookings, never()).save(any());
    }

    @Test
    void 取消場次成功_有人報名已確認時連鎖取消所有報名並歸零人數() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 2, LocalDateTime.now().plusDays(5));
        GroupClassBooking booking1 = booking(member(MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        GroupClassBooking booking2 = booking(member(OTHER_MEMBER_ID), session, GroupClassBookingStatus.BOOKED);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(classBookings.findBySessionIdAndStatus(SESSION_ID, GroupClassBookingStatus.BOOKED))
                .thenReturn(List.of(booking1, booking2));
        stubSessionSaveAndFlushReturnsArgument();
        stubBookingSaveReturnsArgument();

        service.cancelSession(SESSION_ID, true);

        assertThat(session.getStatus()).isEqualTo(GroupClassSessionStatus.CANCELLED);
        assertThat(session.getBookedCount()).isZero();
        assertThat(booking1.getStatus()).isEqualTo(GroupClassBookingStatus.CANCELLED);
        assertThat(booking2.getStatus()).isEqualTo(GroupClassBookingStatus.CANCELLED);
        verify(classBookings, times(2)).save(any(GroupClassBooking.class));
        verify(sessions).saveAndFlush(session);
    }

    @Test
    void 取消場次_有人報名未確認時需二次確認且不動任何狀態() {
        GroupClassSession session = session(GroupClassSessionStatus.SCHEDULED, 10, 2, LocalDateTime.now().plusDays(5));
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.cancelSession(SESSION_ID, false))
                .isInstanceOf(ConfirmationRequiredException.class);
        assertThat(session.getStatus()).isEqualTo(GroupClassSessionStatus.SCHEDULED);
        assertThat(session.getBookedCount()).isEqualTo(2);
        verify(sessions, never()).saveAndFlush(any());
        verify(classBookings, never()).save(any());
    }

    @Test
    void 取消場次_查無此場次回404() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelSession(SESSION_ID, false))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(sessions, never()).saveAndFlush(any());
    }
}
