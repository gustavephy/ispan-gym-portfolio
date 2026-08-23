package tw.com.ispan.gym.controller.coach;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CoachBookingHistoryResponse;
import tw.com.ispan.gym.dto.coach.CoachBookingResponse;
import tw.com.ispan.gym.dto.coach.CoachSlotResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.coach.CoachSlotService;

/**
 * 會員預約教練私教課的對外 API。
 * memberId 一律從 {@link CurrentUserProvider} 取得，不接受前端自行傳入，
 * 避免有人偽造成別的會員身分預約。
 */
@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class CoachBookingController {

    private final CurrentUserProvider currentUserProvider;
    private final CoachBookingService coachBookingService;
    private final CoachSlotService coachSlotService;

    /** 預約指定時段，成功回 201（新建立一筆預約紀錄）。 */
    @PostMapping("/schedules/{slotId}/booking")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<CoachBookingResponse> createBooking(@PathVariable Integer slotId) {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        CoachBookingResponse resp = coachBookingService.createBooking(memberId, slotId);

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 瀏覽指定教練目前可預約的時段，設計上不需登入即可查看（僅顯示公開的排班資訊，不涉及個人資料）。
     * SecurityConfig 已對 {@code GET /api/member/trainers/*&#47;schedules} 補上
     * permitAll，
     * 匿名瀏覽確實可行（見 JwtSecuritySmokeTest）；同前綴下的 /bookings 等個人資料端點
     * 不在放行範圍內，仍需登入。
     */
    @GetMapping("/trainers/{trainerId}/schedules")
    public ResponseEntity<List<CoachSlotResponse>> searchSlots(@PathVariable Integer trainerId) {
        List<CoachSlotResponse> slots = coachSlotService.searchSlots(trainerId);
        return ResponseEntity.status(HttpStatus.OK).body(slots);

    }

    /**
     * 取消指定的預約，成功回 200（更新既有資源狀態，不是新建立）。
     * acknowledgeConsumption 透過 query string 傳遞（DELETE 慣例上不帶 body），
     * 預設 false，語意是「確認取消會消耗這堂額度、不予退還」——
     * 與 CoachSlotController 的 acknowledgeExistingBooking 是同一套 409 確認機制，
     * 但確認的內容不同，因此參數名稱刻意不同，避免混淆。
     */
    @DeleteMapping("/schedules/{bookingId}/cancel")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<CoachBookingResponse> cancelBooking(@PathVariable("bookingId") Integer bookingId,
            @RequestParam(defaultValue = "false") boolean acknowledgeConsumption) {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        CoachBookingResponse resp = coachBookingService.cancelBooking(memberId, bookingId, acknowledgeConsumption);

        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    /**
     * 查詢自己的所有私教課預約紀錄，成功回 200（純查詢，不是新建立資源）。
     * 不帶任何路徑參數，身分一律從 {@link CurrentUserProvider#requireCurrentMemberId()} 取得，
     * 跟 CoachSlotController 的 getMySlots() 是同一套設計原則：不接受前端指定要查誰的資料。
     */
    @GetMapping("/bookings")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<List<CoachBookingHistoryResponse>> getMyBookings() {
        Integer memberId = currentUserProvider.requireCurrentMemberId();
        List<CoachBookingHistoryResponse> bookings = coachBookingService.getMyBookings(memberId);
        return ResponseEntity.status(HttpStatus.OK).body(bookings);
    }
}