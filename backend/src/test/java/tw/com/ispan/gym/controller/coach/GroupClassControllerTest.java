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
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.service.coach.GroupClassService;

/**
 * GET /api/member/groupClasses 的 controller 層測試：standalone MockMvc，
 * service 全 mock，只驗證路由與回應格式；場次篩選（未開始、SCHEDULED）的正確性由 service 層負責。
 */
class GroupClassControllerTest {

    private final GroupClassService groupClassService = mock(GroupClassService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new GroupClassController(groupClassService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 查詢成功回200與場次列表() throws Exception {
        when(groupClassService.getUpcomingSessions()).thenReturn(List.of(
                new GroupClassSessionResponse(1, "空中瑜珈", "A教室",
                        LocalDateTime.of(2030, 1, 1, 10, 0),
                        LocalDateTime.of(2030, 1, 1, 11, 0), 20, 8),
                new GroupClassSessionResponse(2, "飛輪", "B教室",
                        LocalDateTime.of(2030, 1, 2, 18, 0),
                        LocalDateTime.of(2030, 1, 2, 19, 0), 15, 0)));

        mockMvc.perform(get("/api/member/groupClasses"))
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

        mockMvc.perform(get("/api/member/groupClasses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
