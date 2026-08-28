package tw.com.ispan.gym.controller.trainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.coach.AdminCoachSlotResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.dto.trainer.AdminTrainerResponse;
import tw.com.ispan.gym.dto.trainer.TrainerSuspensionCheckResponse;
import tw.com.ispan.gym.enums.coach.SlotStatus;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.coach.CoachSlotService;
import tw.com.ispan.gym.service.trainer.AdminTrainerService;

/**
 * AdminTrainerController 的路由、參數綁定、DTO 驗證、Service 委派與例外映射測試：
 * standalone MockMvc、AdminTrainerService 全 mock，不套 Spring Security。風格比照
 * AdminEmployeeControllerTest；商業邏輯由 AdminTrainerServiceTest 負責，權限由
 * EndpointAuthorizationTest／AdminTrainerMethodSecurityTest 負責。
 */
class AdminTrainerControllerTest {

    private final AdminTrainerService adminTrainerService = mock(AdminTrainerService.class);
    private final CoachSlotService coachSlotService = mock(CoachSlotService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminTrainerController(adminTrainerService, coachSlotService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private AdminTrainerResponse sampleDetail() {
        return new AdminTrainerResponse(7, 3, "陳小華", "coach@example.com", EmployeeStatus.ACTIVE,
                "重量訓練", "五年健身房授課經驗", "專注於初學者的肌力養成。", "https://example.com/trainer/7.jpg",
                TrainerStatus.ACTIVE, null, LocalDateTime.now(), LocalDateTime.now());
    }

    // === GET /api/admin/trainers：參數綁定 ==========================================

    @Test
    void list_預設參數正確傳給Service() throws Exception {
        when(adminTrainerService.list(isNull(), isNull(), eq(false), eq(0), eq(10), isNull(), isNull()))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        mockMvc.perform(get("/api/admin/trainers"))
                .andExpect(status().isOk());

        verify(adminTrainerService).list(isNull(), isNull(), eq(false), eq(0), eq(10), isNull(), isNull());
    }

    @Test
    void list_自訂參數正確傳遞() throws Exception {
        when(adminTrainerService.list(eq("陳"), eq(TrainerStatus.UNAVAILABLE), eq(true),
                eq(2), eq(25), eq("name"), eq("desc")))
                .thenReturn(Page.empty(PageRequest.of(2, 25)));

        mockMvc.perform(get("/api/admin/trainers")
                        .param("keyword", "陳")
                        .param("status", "UNAVAILABLE")
                        .param("deleted", "true")
                        .param("page", "2")
                        .param("size", "25")
                        .param("sort", "name")
                        .param("direction", "desc"))
                .andExpect(status().isOk());
    }

    // === GET /api/admin/trainers/{trainerId} ========================================

    @Test
    void getById_成功回傳含員工狀態與deletedAt() throws Exception {
        when(adminTrainerService.getById(7)).thenReturn(sampleDetail());

        mockMvc.perform(get("/api/admin/trainers/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerId").value(7))
                .andExpect(jsonPath("$.employeeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value("coach@example.com"));
    }

    @Test
    void getById_不存在時回404() throws Exception {
        when(adminTrainerService.getById(999)).thenThrow(new ResourceNotFoundException("找不到此教練"));

        mockMvc.perform(get("/api/admin/trainers/999"))
                .andExpect(status().isNotFound());
    }

    // === GET /api/admin/trainers/{trainerId}/coach-slots ============================

    @Test
    void getCoachSlots_預設參數正確傳給Service() throws Exception {
        when(coachSlotService.getSlotsForAdmin(eq(7), eq(0), eq(10), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<>(java.util.List.of(), 0, 10, 0, 0));

        mockMvc.perform(get("/api/admin/trainers/7/coach-slots"))
                .andExpect(status().isOk());

        verify(coachSlotService).getSlotsForAdmin(eq(7), eq(0), eq(10), isNull(), isNull(), isNull());
    }

    @Test
    void getCoachSlots_成功回傳含預約會員姓名() throws Exception {
        AdminCoachSlotResponse slot = new AdminCoachSlotResponse(101, 7,
                LocalDateTime.of(2026, 9, 1, 10, 0), LocalDateTime.of(2026, 9, 1, 11, 0),
                SlotStatus.BOOKED, 55, tw.com.ispan.gym.enums.coach.CoachBookingStatus.PENDING, 12, "王小明");
        when(coachSlotService.getSlotsForAdmin(eq(7), eq(0), eq(10), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<>(java.util.List.of(slot), 0, 10, 1, 1));

        mockMvc.perform(get("/api/admin/trainers/7/coach-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].memberName").value("王小明"))
                .andExpect(jsonPath("$.items[0].bookingStatus").value("PENDING"));
    }

    @Test
    void getCoachSlots_教練不存在時回404() throws Exception {
        when(coachSlotService.getSlotsForAdmin(eq(999), eq(0), eq(10), isNull(), isNull(), isNull()))
                .thenThrow(new ResourceNotFoundException("找不到此教練"));

        mockMvc.perform(get("/api/admin/trainers/999/coach-slots"))
                .andExpect(status().isNotFound());
    }

    // === PUT /api/admin/trainers/{trainerId} ========================================

    @Test
    void update_成功委派並回傳() throws Exception {
        when(adminTrainerService.update(eq(7), any())).thenReturn(sampleDetail());

        mockMvc.perform(put("/api/admin/trainers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "experience": "五年健身房授課經驗",
                                  "bio": "專注於初學者的肌力養成。", "profileImageUrl": "https://example.com/trainer/7.jpg" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialty").value("重量訓練"));
    }

    @Test
    void update_缺bio時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/trainers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "experience": "五年" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminTrainerService, never()).update(any(), any());
    }

    // === POST /api/admin/trainers/{trainerId}/status ================================

    @Test
    void changeStatus_成功委派() throws Exception {
        when(adminTrainerService.changeStatus(eq(7), any())).thenReturn(sampleDetail());

        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "UNAVAILABLE" }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void changeStatus_缺status時回400() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(adminTrainerService, never()).changeStatus(any(), any());
    }

    @Test
    void changeStatus_非法值時回400() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "RETIRED" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminTrainerService, never()).changeStatus(any(), any());
    }

    @Test
    void changeStatus_重複狀態時映射409() throws Exception {
        when(adminTrainerService.changeStatus(eq(7), any()))
                .thenThrow(new BusinessRuleException("此教練已經是指定狀態"));

        mockMvc.perform(post("/api/admin/trainers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "ACTIVE" }
                                """))
                .andExpect(status().isConflict());
    }

    // === GET /api/admin/trainers/{trainerId}/suspension-check =======================

    @Test
    void suspensionCheck_成功回傳阻塞明細() throws Exception {
        TrainerSuspensionCheckResponse response = new TrainerSuspensionCheckResponse(false,
                new TrainerSuspensionCheckResponse.Blockers(2, 1, 1, 3));
        when(adminTrainerService.suspensionCheck(7)).thenReturn(response);

        mockMvc.perform(get("/api/admin/trainers/7/suspension-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSuspend").value(false))
                .andExpect(jsonPath("$.blockers.openSlots").value(2))
                .andExpect(jsonPath("$.blockers.activeBookings").value(1))
                .andExpect(jsonPath("$.blockers.activePrivateCourses").value(1))
                .andExpect(jsonPath("$.blockers.upcomingGroupClassSessions").value(3));
    }

    // === POST /api/admin/trainers/{trainerId}/suspend ================================

    @Test
    void suspend_成功回傳204() throws Exception {
        mockMvc.perform(post("/api/admin/trainers/7/suspend"))
                .andExpect(status().isNoContent());

        verify(adminTrainerService).suspend(7);
    }

    @Test
    void suspend_有阻塞時映射409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessRuleException("教練仍有未完成業務，無法停用"))
                .when(adminTrainerService).suspend(7);

        mockMvc.perform(post("/api/admin/trainers/7/suspend"))
                .andExpect(status().isConflict());
    }

    // === POST /api/admin/trainers/{trainerId}/restore ================================

    @Test
    void restore_成功回傳教練資料() throws Exception {
        when(adminTrainerService.restore(7)).thenReturn(sampleDetail());

        mockMvc.perform(post("/api/admin/trainers/7/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerId").value(7));
    }

    @Test
    void restore_未停用時映射409() throws Exception {
        when(adminTrainerService.restore(7))
                .thenThrow(new BusinessRuleException("此教練目前並未停用，無需恢復"));

        mockMvc.perform(post("/api/admin/trainers/7/restore"))
                .andExpect(status().isConflict());
    }
}
