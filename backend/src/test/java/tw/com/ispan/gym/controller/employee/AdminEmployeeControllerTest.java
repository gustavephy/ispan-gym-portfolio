package tw.com.ispan.gym.controller.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.employee.AdminEmployeeResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeRoleUpdateRequest;
import tw.com.ispan.gym.dto.employee.AdminEmployeeSummaryResponse;
import tw.com.ispan.gym.dto.employee.AdminEmployeeUpdateRequest;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.employee.AdminEmployeeService;

/**
 * AdminEmployeeController 的路由、參數綁定、DTO 驗證、Service 委派與例外映射測試：
 * standalone MockMvc、AdminEmployeeService 全 mock，不套 Spring Security。
 *
 * <p>這支不驗權限——AdminEmployeeService 本身的商業邏輯（狀態轉換、悲觀鎖、排序白名單等）
 * 由 AdminEmployeeServiceTest 負責；URL 層與方法層授權分別由 EndpointAuthorizationTest
 * （真牌 SecurityConfig，只能證明 URL 層）與 AdminEmployeeMethodSecurityTest（刻意放寬
 * URL 層，隔離驗證 @PreAuthorize）負責。三支測試合起來才涵蓋完整責任範圍，比照
 * AdminMemberControllerTest 的分工風格。
 */
class AdminEmployeeControllerTest {

    private final AdminEmployeeService adminEmployeeService = mock(AdminEmployeeService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminEmployeeController(adminEmployeeService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private AdminEmployeeResponse sampleDetail() {
        return new AdminEmployeeResponse(
                3, "王小美", "employee@example.com", "小美", "王", "0922222222",
                EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE, LocalDate.of(2020, 1, 1),
                false, null, null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    // === GET /api/admin/employees：參數綁定與傳遞 ==================================

    @Test
    void list_預設參數正確傳給Service() throws Exception {
        when(adminEmployeeService.list(isNull(), isNull(), isNull(), eq(0), eq(10), isNull(), isNull()))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        mockMvc.perform(get("/api/admin/employees"))
                .andExpect(status().isOk());

        verify(adminEmployeeService).list(isNull(), isNull(), isNull(), eq(0), eq(10), isNull(), isNull());
    }

    @Test
    void list_自訂參數正確傳遞() throws Exception {
        when(adminEmployeeService.list(eq("small"), eq(EmployeeStatus.ACTIVE), eq(EmployeeRole.ADMIN),
                eq(2), eq(25), eq("name"), eq("desc")))
                .thenReturn(Page.empty(PageRequest.of(2, 25)));

        mockMvc.perform(get("/api/admin/employees")
                        .param("keyword", "small")
                        .param("status", "ACTIVE")
                        .param("role", "ADMIN")
                        .param("page", "2")
                        .param("size", "25")
                        .param("sort", "name")
                        .param("direction", "desc"))
                .andExpect(status().isOk());

        verify(adminEmployeeService).list(eq("small"), eq(EmployeeStatus.ACTIVE), eq(EmployeeRole.ADMIN),
                eq(2), eq(25), eq("name"), eq("desc"));
    }

    @Test
    void list_回傳PageJSON含content與分頁metadata() throws Exception {
        AdminEmployeeSummaryResponse summary = new AdminEmployeeSummaryResponse(
                3, "王小美", "employee@example.com", "0922222222",
                EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE, LocalDate.of(2020, 1, 1),
                LocalDateTime.of(2026, 1, 1, 0, 0));
        Page<AdminEmployeeSummaryResponse> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1);
        when(adminEmployeeService.list(any(), any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/admin/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(3))
                .andExpect(jsonPath("$.content[0].displayName").value("王小美"))
                .andExpect(jsonPath("$.content[0].email").value("employee@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void list_status非法值回400且不呼叫Service() throws Exception {
        mockMvc.perform(get("/api/admin/employees").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).list(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void list_role非法值回400且不呼叫Service() throws Exception {
        mockMvc.perform(get("/api/admin/employees").param("role", "SUPER_ADMIN"))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).list(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void list_ServiceThrowInvalidRequest時映射400() throws Exception {
        when(adminEmployeeService.list(any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InvalidRequestException("頁碼不可小於 0"));

        mockMvc.perform(get("/api/admin/employees").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // === GET /api/admin/employees/{employeeId} =====================================

    @Test
    void getById_成功回傳資料() throws Exception {
        when(adminEmployeeService.getById(3)).thenReturn(sampleDetail());

        mockMvc.perform(get("/api/admin/employees/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(3))
                .andExpect(jsonPath("$.email").value("employee@example.com"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist());
    }

    @Test
    void getById_ServiceThrowResourceNotFound時映射404() throws Exception {
        when(adminEmployeeService.getById(3)).thenThrow(new ResourceNotFoundException("找不到此員工"));

        mockMvc.perform(get("/api/admin/employees/3"))
                .andExpect(status().isNotFound());
    }

    // === PUT /api/admin/employees/{employeeId} =====================================

    @Test
    void update_有效body正確映射四個欄位並委派() throws Exception {
        ArgumentCaptor<AdminEmployeeUpdateRequest> captor =
                ArgumentCaptor.forClass(AdminEmployeeUpdateRequest.class);
        when(adminEmployeeService.update(eq(3), captor.capture())).thenReturn(sampleDetail());

        mockMvc.perform(put("/api/admin/employees/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "小明",
                                  "lastName": "林",
                                  "phone": "0933333333",
                                  "hireDate": "2021-06-01"
                                }
                                """))
                .andExpect(status().isOk());

        AdminEmployeeUpdateRequest captured = captor.getValue();
        assertThat(captured.getFirstName()).isEqualTo("小明");
        assertThat(captured.getLastName()).isEqualTo("林");
        assertThat(captured.getPhone()).isEqualTo("0933333333");
        assertThat(captured.getHireDate()).isEqualTo(LocalDate.of(2021, 6, 1));
    }

    @Test
    void update_缺firstName時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/employees/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lastName": "林", "phone": "0933333333", "hireDate": "2021-06-01" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).update(any(), any());
    }

    @Test
    void update_缺hireDate時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/employees/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "小明", "lastName": "林", "phone": "0933333333" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).update(any(), any());
    }

    /**
     * 台灣手機格式（^09\d{8}$、固定 10 碼）：非 09 開頭／不足或超過 10 碼／含非數字／空白
     * 一律回 400 且不呼叫 Service，不能繞過前端直接寫入非法號碼。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "0812345678",            // 非 09 開頭
            "091234567",             // 不足 10 碼
            "09123456789",           // 超過 10 碼
            "123456789012345678901", // 遠超過長度上限
            "09abcdefgh",            // 含非數字
            "   "                    // 空白
    })
    void update_phone格式不符時回400且不呼叫Service(String badPhone) throws Exception {
        mockMvc.perform(put("/api/admin/employees/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "小明", "lastName": "林", "phone": "%s", "hireDate": "2021-06-01" }
                                """.formatted(badPhone)))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).update(any(), any());
    }

    // === POST /api/admin/employees/{employeeId}/role ================================

    @Test
    void changeRole_有效body正確映射role並委派() throws Exception {
        ArgumentCaptor<AdminEmployeeRoleUpdateRequest> captor =
                ArgumentCaptor.forClass(AdminEmployeeRoleUpdateRequest.class);
        when(adminEmployeeService.changeRole(eq(3), captor.capture())).thenReturn(sampleDetail());

        mockMvc.perform(post("/api/admin/employees/3/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "ADMIN" }
                                """))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getRole()).isEqualTo(EmployeeRole.ADMIN);
    }

    @Test
    void changeRole_缺role時回400且不呼叫Service() throws Exception {
        mockMvc.perform(post("/api/admin/employees/3/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).changeRole(any(), any());
    }

    @Test
    void changeRole_role為非法列舉值時回400() throws Exception {
        mockMvc.perform(post("/api/admin/employees/3/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "SUPER_ADMIN" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminEmployeeService, never()).changeRole(any(), any());
    }

    @Test
    void changeRole_ServiceThrowBusinessRule時映射409() throws Exception {
        when(adminEmployeeService.changeRole(eq(3), any()))
                .thenThrow(new BusinessRuleException("此員工已經是指定角色"));

        mockMvc.perform(post("/api/admin/employees/3/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "ADMIN" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    // === POST /api/admin/employees/{employeeId}/suspend ／ /activate ===============

    @Test
    void suspend_正確傳入employeeId() throws Exception {
        mockMvc.perform(post("/api/admin/employees/3/suspend"))
                .andExpect(status().isOk());

        verify(adminEmployeeService).suspend(3);
    }

    @Test
    void suspend_ServiceThrowBusinessRule時映射409() throws Exception {
        doThrow(new BusinessRuleException("必須至少保留一位有效管理員，無法停用最後一位"))
                .when(adminEmployeeService).suspend(3);

        mockMvc.perform(post("/api/admin/employees/3/suspend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void suspend_ServiceThrowResourceNotFound時映射404() throws Exception {
        doThrow(new ResourceNotFoundException("找不到此員工")).when(adminEmployeeService).suspend(3);

        mockMvc.perform(post("/api/admin/employees/3/suspend"))
                .andExpect(status().isNotFound());
    }

    @Test
    void activate_正確傳入employeeId() throws Exception {
        mockMvc.perform(post("/api/admin/employees/3/activate"))
                .andExpect(status().isOk());

        verify(adminEmployeeService).activate(3);
    }

    @Test
    void activate_ServiceThrowBusinessRule時映射409() throws Exception {
        doThrow(new BusinessRuleException("此員工已是啟用狀態")).when(adminEmployeeService).activate(3);

        mockMvc.perform(post("/api/admin/employees/3/activate"))
                .andExpect(status().isConflict());
    }
}
