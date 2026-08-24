package tw.com.ispan.gym.controller.course;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.dto.trainer.TrainerDto;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.course.PrivateCourseService;

/**
 * AdminPrivateCourseController 的路由與 Service 委派、例外映射測試：standalone MockMvc，
 * PrivateCourseService 全 mock，不套 Spring Security（權限由 EndpointAuthorizationTest 負責）。
 */
class AdminPrivateCourseControllerTest {

    private final PrivateCourseService privateCourseService = mock(PrivateCourseService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminPrivateCourseController(privateCourseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private PrivateCourseResponse sample(CourseStatus status) {
        return new PrivateCourseResponse(30, "重量訓練體驗課", 1, 1, BigDecimal.TEN, null, null, false,
                status, tw.com.ispan.gym.enums.course.CourseType.PRIVATE, null, null, null, null, null);
    }

    @Test
    void getByTrainer_成功委派() throws Exception {
        when(privateCourseService.getCoursesByTrainerForAdmin(7)).thenReturn(List.of(sample(CourseStatus.ACTIVE)));

        mockMvc.perform(get("/api/admin/private-courses").param("trainerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(30));
    }

    @Test
    void deactivate_成功回200() throws Exception {
        when(privateCourseService.deactivateByAdmin(30)).thenReturn(sample(CourseStatus.INACTIVE));

        mockMvc.perform(post("/api/admin/private-courses/30/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    void deactivate_課程不存在時回404() throws Exception {
        when(privateCourseService.deactivateByAdmin(999)).thenThrow(new ResourceNotFoundException("課程不存在"));

        mockMvc.perform(post("/api/admin/private-courses/999/deactivate"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activate_成功回200() throws Exception {
        when(privateCourseService.activateByAdmin(30)).thenReturn(sample(CourseStatus.ACTIVE));

        mockMvc.perform(post("/api/admin/private-courses/30/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void activate_教練非可服務狀態時映射409() throws Exception {
        when(privateCourseService.activateByAdmin(eq(30)))
                .thenThrow(new BusinessRuleException("教練目前無法承接新業務（非在職或非可服務狀態）"));

        mockMvc.perform(post("/api/admin/private-courses/30/activate"))
                .andExpect(status().isConflict());
    }
}
