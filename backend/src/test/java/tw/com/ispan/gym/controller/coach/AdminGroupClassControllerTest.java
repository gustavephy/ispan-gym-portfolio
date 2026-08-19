package tw.com.ispan.gym.controller.coach;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.coach.GroupClassService;

/**
 * POST /api/admin/group-classes 的 controller 層測試：standalone MockMvc，
 * service 全 mock，只驗證路由、@Valid 檢核與 ApiException → JSON 錯誤格式的對應；
 * 教室重疊等商業規則的正確性由 service 層負責，ADMIN 角色保護由 JwtSecuritySmokeTest 統一驗證。
 */
class AdminGroupClassControllerTest {

    private final GroupClassService groupClassService = mock(GroupClassService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminGroupClassController(groupClassService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static final String VALID_BODY = """
            {
              "groupCourseId": 1,
              "roomName": "A教室",
              "startAt": "2030-01-01T10:00:00",
              "endAt": "2030-01-01T11:00:00"
            }
            """;

    @Test
    void 建立成功回201與場次資料() throws Exception {
        when(groupClassService.createSession(any())).thenReturn(new GroupClassSessionResponse(
                5, 1, 1, "王 教練", "空中瑜珈", "A教室",
                LocalDateTime.of(2030, 1, 1, 10, 0),
                LocalDateTime.of(2030, 1, 1, 11, 0),
                20, 0, 20, false, GroupClassSessionStatus.SCHEDULED, 0,
                LocalDateTime.of(2029, 12, 1, 9, 0), LocalDateTime.of(2029, 12, 1, 9, 0)));

        mockMvc.perform(post("/api/admin/group-classes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.courseName").value("空中瑜珈"))
                .andExpect(jsonPath("$.remainingSeats").value(20));
    }

    @Test
    void 缺少必填欄位回400() throws Exception {
        mockMvc.perform(post("/api/admin/group-classes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "roomName": "A教室",
                          "startAt": "2030-01-01T10:00:00",
                          "endAt": "2030-01-01T11:00:00"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 結束時間早於開始時間回400() throws Exception {
        mockMvc.perform(post("/api/admin/group-classes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "groupCourseId": 1,
                          "roomName": "A教室",
                          "startAt": "2030-01-01T11:00:00",
                          "endAt": "2030-01-01T10:00:00"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 課程不存在回404() throws Exception {
        when(groupClassService.createSession(any()))
                .thenThrow(new ResourceNotFoundException("查無此課程"));

        mockMvc.perform(post("/api/admin/group-classes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 教室時段重疊回409() throws Exception {
        when(groupClassService.createSession(any()))
                .thenThrow(new BusinessRuleException("此教室已有重疊時段"));

        mockMvc.perform(post("/api/admin/group-classes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }
}
