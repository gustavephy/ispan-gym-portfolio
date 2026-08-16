package tw.com.ispan.gym.controller.coach;

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

import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.coach.GroupClassService;

/**
 * GET /api/member/group-classes 的 controller 層測試：standalone MockMvc，
 * service 全 mock，只驗證路由與回應格式；場次篩選（未開始、SCHEDULED）的正確性由 service 層負責。
 */
class GroupClassControllerTest {

    private final GroupClassService groupClassService = mock(GroupClassService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new GroupClassController(groupClassService, currentUserProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static GroupClassSessionResponse session(Integer id, String courseName, String roomName,
            LocalDateTime startAt, LocalDateTime endAt, int capacity, int bookedCount) {
        return new GroupClassSessionResponse(id, 1, 1, "王 教練", courseName, roomName,
                startAt, endAt, capacity, bookedCount, capacity - bookedCount,
                bookedCount >= capacity, GroupClassSessionStatus.SCHEDULED, 0,
                LocalDateTime.of(2029, 12, 1, 9, 0), LocalDateTime.of(2029, 12, 1, 9, 0));
    }

    @Test
    void 查詢成功回200與場次列表() throws Exception {
        when(groupClassService.getUpcomingSessions()).thenReturn(List.of(
                session(1, "空中瑜珈", "A教室",
                        LocalDateTime.of(2030, 1, 1, 10, 0),
                        LocalDateTime.of(2030, 1, 1, 11, 0), 20, 12),
                session(2, "飛輪", "B教室",
                        LocalDateTime.of(2030, 1, 2, 18, 0),
                        LocalDateTime.of(2030, 1, 2, 19, 0), 15, 15)));

        mockMvc.perform(get("/api/member/group-classes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].courseName").value("空中瑜珈"))
                .andExpect(jsonPath("$[0].remainingSeats").value(8))
                .andExpect(jsonPath("$[1].remainingSeats").value(0));
    }

    @Test
    void 無場次回200空陣列() throws Exception {
        when(groupClassService.getUpcomingSessions()).thenReturn(List.of());

        mockMvc.perform(get("/api/member/group-classes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
