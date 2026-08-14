package tw.com.ispan.gym.service.coach;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CreateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.GroupClassBookingResponse;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.entity.coach.GroupClassBooking;
import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.GroupClassBookingRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;

@Service
@RequiredArgsConstructor
public class GroupClassService {

    private final GroupClassSessionRepository groupClassSessionRepository;
    private final GroupCourseRepository groupCourseRepository;
    private final MemberSubscriptionRepository memberSubscriptionRepository;
    private final GroupClassBookingRepository groupClassBookingRepository;

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public List<GroupClassSessionResponse> getUpcomingSessions() {
        List<GroupClassSession> upcomingSessions = groupClassSessionRepository
                .findByStatusAndStartAtAfterOrderByStartAtAsc(GroupClassSessionStatus.SCHEDULED, LocalDateTime.now());
        List<GroupClassSessionResponse> resp = upcomingSessions.stream()
                .map(session -> new GroupClassSessionResponse(session.getId(), session.getCourse().getName(),
                        session.getRoomName(), session.getStartAt(), session.getEndAt(), session.getCapacity(),
                        session.getRemainingSeats())

                ).toList();
        return resp;
    }

    /**
     * 管理層建立團課場次。操作者角色限定 ADMIN（由 Controller 的 @PreAuthorize 把關），
     * 不需要解析「本人」身分——GroupClassSession 沒有綁定建立者，
     * 教練是誰由 groupCourseId 對應的 GroupCourse 決定，不是這裡指定的。
     *
     * 檢查順序：課程不存在 → 404；同教室同時段已有其他（未取消）場次 → 409（教室衝突，
     * 邏輯跟 CoachSlotService 的時段重疊檢查同一套，只是比對對象從教練換成教室）。
     * 全部通過才真的建立場次。
     *
     * capacity 允許不填，不填時 fallback 回該團課課程的 maxCapacity，
     * 讓管理層可以視實際教室調整（例如某天借到比較小的教室，容量就設低一點）。
     */
    @Transactional
    public GroupClassSessionResponse createSession(CreateGroupClassSessionRequest request) {
        Integer groupCourseId = request.getGroupCourseId();
        GroupCourse groupCourse = groupCourseRepository.findById(groupCourseId)
                .orElseThrow(() -> new ResourceNotFoundException("查無此課程"));

        List<GroupClassSession> existing = groupClassSessionRepository
                .findOverlappingByRoom(request.getRoomName(), request.getStartAt(), request.getEndAt());

        if (!existing.isEmpty()) {
            throw new BusinessRuleException("此教室已有重疊時段");
        }

        GroupClassSession groupClassSession = new GroupClassSession();
        groupClassSession.setCourse(groupCourse);
        groupClassSession.setRoomName(request.getRoomName());
        groupClassSession.setStartAt(request.getStartAt());
        groupClassSession.setEndAt(request.getEndAt());
        groupClassSession.setStatus(GroupClassSessionStatus.SCHEDULED);
        groupClassSession
                .setCapacity(request.getCapacity() != null ? request.getCapacity() : groupCourse.getMaxCapacity());

        GroupClassSession saved = groupClassSessionRepository.save(groupClassSession);
        return new GroupClassSessionResponse(saved.getId(), groupCourse.getName(), saved.getRoomName(),
                saved.getStartAt(), saved.getEndAt(), saved.getCapacity(), saved.getRemainingSeats());
    }

    /**
     * 會員報名團課場次。資格判斷依據是「有效訂閱」，不是額度（團課不走購買流程）。
     * 檢查順序：查無場次 → 404；查無有效訂閱 → 409；已報名過此場次 → 409；
     * 額滿或場次已取消由 session.book() 自己判斷並丟例外，這裡不用重複寫。
     */
    @Transactional
    public GroupClassBookingResponse bookSession(Integer memberId, Integer sessionId) {
        GroupClassSession groupClassSession = groupClassSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("查無此團課場次"));

        Optional<MemberSubscription> subscription = memberSubscriptionRepository
                .findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(memberId,
                        LocalDateTime.now(), SubscriptionStatus.ACTIVE);
        if (subscription.isEmpty()) {
            throw new BusinessRuleException("查無有效訂閱，請確認訂閱狀態");
        }

        Optional<GroupClassBooking> book = groupClassBookingRepository.findBySessionIdAndMemberIdAndStatus(sessionId,
                memberId, GroupClassBookingStatus.BOOKED);
        if (!book.isEmpty()) {
            throw new BusinessRuleException("已報名此場次，請勿重複報名");
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("查無此會員"));

        groupClassSession.book();
        groupClassSessionRepository.save(groupClassSession);

        GroupClassBooking groupClassBooking = new GroupClassBooking();
        groupClassBooking.setSession(groupClassSession);
        groupClassBooking.setMember(member);
        groupClassBooking.setStatus(GroupClassBookingStatus.BOOKED);

        GroupClassBooking saveBooking = groupClassBookingRepository.save(groupClassBooking);
        GroupClassBookingResponse resp = new GroupClassBookingResponse(saveBooking.getId(), sessionId,
                groupClassSession.getCourse().getName(), groupClassSession.getRoomName(),
                groupClassSession.getStartAt(), groupClassSession.getEndAt(), saveBooking.getStatus());
        return resp;
    }

}