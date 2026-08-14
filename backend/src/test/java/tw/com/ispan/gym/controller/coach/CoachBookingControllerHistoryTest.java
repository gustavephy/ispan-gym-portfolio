package tw.com.ispan.gym.controller.coach;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.coach.CoachBookingHistoryResponse;
import tw.com.ispan.gym.enums.coach.CoachBookingStatus;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.CoachBookingService;
import tw.com.ispan.gym.service.coach.CoachSlotService;

/**
 * GET /api/member/bookings（預約歷史查詢）的 controller 層測試：standalone MockMvc，
 * service 全 mock，只驗證路由、memberId 來自 CurrentUserProvider 與回應格式；
 * 額度快照的取值邏輯由 CoachBookingService 負責，MEMBER 角色保護由 JwtSecuritySmokeTest 統一驗證。
 */
class CoachBookingControllerHistoryTest {

    private static final Integer MEMBER_ID = 1;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final CoachBookingService coachBookingService = mock(CoachBookingService.class);
    private final CoachSlotService coachSlotService = mock(CoachSlotService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CoachBookingController(currentUser, coachBookingService, coachSlotService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 查詢成功回200與預約歷史() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(coachBookingService.getMyBookings(MEMBER_ID)).thenReturn(List.of(
                new CoachBookingHistoryResponse(10, 3, 2,
                        LocalDateTime.of(2030, 1, 1, 10, 0),
                        LocalDateTime.of(2030, 1, 1, 11, 0),
                        CoachBookingStatus.PENDING, "ABC123", 7)));

        mockMvc.perform(get("/api/member/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].trainerId").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].checkInKey").value("ABC123"))
                .andExpect(jsonPath("$[0].remainingCourseSnapshot").value(7));
    }

    @Test
    void 無稽核紀錄的舊預約快照為null() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(coachBookingService.getMyBookings(MEMBER_ID)).thenReturn(List.of(
                new CoachBookingHistoryResponse(11, 4, 2,
                        LocalDateTime.of(2029, 1, 1, 10, 0),
                        LocalDateTime.of(2029, 1, 1, 11, 0),
                        CoachBookingStatus.COMPLETED, "OLD999", null)));

        mockMvc.perform(get("/api/member/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].remainingCourseSnapshot", nullValue()));
    }

    @Test
    void 無任何預約回200空陣列() throws Exception {
        when(currentUser.requireCurrentMemberId()).thenReturn(MEMBER_ID);
        when(coachBookingService.getMyBookings(MEMBER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/member/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
