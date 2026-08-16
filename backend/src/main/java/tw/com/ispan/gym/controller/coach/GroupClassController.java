package tw.com.ispan.gym.controller.coach;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import tw.com.ispan.gym.dto.coach.GroupClassBookingResponse;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.GroupClassService;

@RestController
@RequestMapping("/api/member/group-classes")
@RequiredArgsConstructor
public class GroupClassController {

    private final GroupClassService groupClassService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 查看即將開始、開放報名的團課場次。刻意不加 @PreAuthorize（不限定要有 MEMBER 角色），
     * 跟 CoachBookingController.searchSlots() 的既有取捨一致：瀏覽課表本身不該要求登入權限，
     * 只有實際「報名」的動作才需要驗證身分。
     * SecurityConfig 已對 {@code GET /api/member/group-classes} 補上 permitAll，
     * 匿名瀏覽確實可行（見 JwtSecuritySmokeTest）；報名／取消報名仍需 MEMBER 身分。
     */
    @GetMapping
    public ResponseEntity<List<GroupClassSessionResponse>> getUpcomingSessions() {
        List<GroupClassSessionResponse> sessions = groupClassService.getUpcomingSessions();
        return ResponseEntity.status(HttpStatus.OK).body(sessions);
    }

    /**
     * 會員報名指定的團課場次，成功回 201。memberId 從 {@link CurrentUserProvider} 取得，
     * 避免前端自行傳入他人 id 造成安全問題。
     */
    @PostMapping("/{sessionId}/booking")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<GroupClassBookingResponse> bookSession(@PathVariable Integer sessionId) {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        GroupClassBookingResponse resp = groupClassService.bookSession(memberId, sessionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 會員取消自己的團課報名，成功回 200。
     */
    @DeleteMapping("/bookings/{bookingId}")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<GroupClassBookingResponse> cancelBooking(@PathVariable("bookingId") Integer bookingId) {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        GroupClassBookingResponse resp = groupClassService.cancelBooking(memberId, bookingId);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

}