package tw.com.ispan.gym.controller.coach;


import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CheckInRequest;
import tw.com.ispan.gym.dto.coach.CoachBookingResponse;
import tw.com.ispan.gym.dto.coach.CoachSlotResponse;
import tw.com.ispan.gym.dto.coach.CreateCoachSlotRequest;
import tw.com.ispan.gym.dto.coach.UpdateCoachSlotRequest;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.coach.CoachSlotService;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 教練排班（私教課時段）的對外 API，路徑統一在 /api/trainer/schedules 底下。
 * 所有端點都限定 TRAINER 角色，登入者帳號 id 一律從 {@link CurrentUserProvider} 取得
 * （service 層再解析為 Trainer），不接受前端自己傳教練身分，避免偽造別的教練操作。
 */
@RestController
@RequestMapping("/api/trainer")
@RequiredArgsConstructor
public class CoachSlotController {

    private final CoachSlotService coachSlotService;
    private final CurrentUserProvider currentUserProvider;
    private final CoachBookingService coachBookingService;

    /** 建立新時段，成功回 201（新建立了一個資源）。 */
    @PostMapping("/schedules")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> createSlot(@Valid @RequestBody CreateCoachSlotRequest request) {
        Integer accountId = currentUserProvider.requireCurrentUserId();
        CoachSlotResponse resp = coachSlotService.createSlot(accountId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 取消（軟刪除）時段，成功回 200。
     * acknowledgeExistingBooking 透過 query string 傳遞（DELETE 慣例上不帶 body），
     * 預設 false：前端沒特別加這個參數時，一律視為「尚未確認」，
     * 這樣才能真正擋住已預約的時段被誤刪，不會因為前端忘記帶參數就被自動放行。
     */
    @DeleteMapping("/schedules/{slotId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> cancelSlot(@PathVariable("slotId") Integer slotId,
            @RequestParam(defaultValue = "false") boolean acknowledgeExistingBooking) {
        Integer accountId = currentUserProvider.requireCurrentUserId();
        CoachSlotResponse resp = coachBookingService.cancelSlotWithCascade(accountId, slotId,
                acknowledgeExistingBooking);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    /**
     * 分頁查詢自己的所有時段（含已取消的舊紀錄）。
     * status 對應表格實際顯示的四種狀態（AVAILABLE／PENDING／COMPLETED／CANCELLED），
     * 詳見 {@link tw.com.ispan.gym.service.coach.CoachSlotService#getMySlots}。
     */
    @GetMapping("/schedules")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<PageResponse<CoachSlotResponse>> getMySlots(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        Integer accountId = currentUserProvider.requireCurrentUserId();
        PageResponse<CoachSlotResponse> slots = coachSlotService.getMySlots(accountId, page, size, status, dateFrom,
                dateTo);

        return ResponseEntity.status(HttpStatus.OK).body(slots);
    }

    /**
     * 修改一筆既有時段的時間，成功回 200（修改既有資源，不是新建，所以不是 201）。
     * acknowledgeExistingBooking 這次是 request body 裡的欄位（PUT 有 body 可以用），
     * 跟 cancelSlot 用 query string 的方式不同，是因為兩者能承載資料的管道天生不一樣。
     */
    @PutMapping("/schedules/{slotId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> updateSlot(@PathVariable("slotId") Integer slotId,
            @Valid @RequestBody UpdateCoachSlotRequest request) {
        Integer accountId = currentUserProvider.requireCurrentUserId();
        CoachSlotResponse resp = coachSlotService.updateSlot(accountId, slotId, request);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    @PutMapping("/schedules/{bookingId}/checkin")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachBookingResponse> checkIn(@PathVariable("bookingId") Integer bookingId,
            @Valid @RequestBody CheckInRequest request) {
        Integer accountId = currentUserProvider.requireCurrentUserId();
        CoachBookingResponse resp = coachBookingService.checkIn(accountId, bookingId, request.getCheckInKey());
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }
}