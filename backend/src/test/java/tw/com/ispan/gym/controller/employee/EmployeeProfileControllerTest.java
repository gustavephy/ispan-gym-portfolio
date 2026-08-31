package tw.com.ispan.gym.controller.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.employee.EmployeeProfileResponse;
import tw.com.ispan.gym.dto.employee.EmployeeProfileUpdateRequest;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.employee.EmployeeProfileService;

/**
 * EmployeeProfileController 的路由、request validation、Service 委派與例外映射測試：
 * standalone MockMvc，EmployeeProfileService／CurrentUserProvider 全 mock，不套 Spring Security。
 *
 * <p>這支不驗權限——EmployeeProfileService 本身的商業邏輯由 EmployeeProfileServiceTest
 * 負責；URL 層與方法層授權分別由 EndpointAuthorizationTest（真牌 SecurityConfig，只能
 * 證明 URL 層）與 AdminEmployeeMethodSecurityTest（刻意放寬 URL 層，隔離驗證
 * @PreAuthorize）負責。三支測試合起來才涵蓋完整責任範圍。
 *
 * <p>Member 目前沒有對應的 MemberProfileControllerTest（自助 PUT 的 request validation
 * 只靠 Controller 層 @Valid，沒有獨立測試覆蓋）；Employee 這裡刻意補上，因為「本人只能改
 * phone」是這次規格特別拍板、值得在 Controller 層單獨釘住的權限邊界。
 */
class EmployeeProfileControllerTest {

    private final EmployeeProfileService profileService = mock(EmployeeProfileService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final tw.com.ispan.gym.service.file.FileService fileService = mock(tw.com.ispan.gym.service.file.FileService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new EmployeeProfileController(profileService, currentUser, fileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private EmployeeProfileResponse sampleResponse() {
        return new EmployeeProfileResponse(
                3, "employee@example.com", "小美", "王", "0922222222",
                EmployeeRole.EMPLOYEE, EmployeeStatus.ACTIVE, LocalDate.of(2020, 1, 1),
                false, LocalDateTime.now(), null, null, null, null);
    }

    // === GET /api/employee/profile ==================================================

    @Test
    void getMyProfile_以requireCurrentEmployeeId取得employeeId並委派() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/employee/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(3))
                .andExpect(jsonPath("$.email").value("employee@example.com"))
                .andExpect(jsonPath("$.isTrainer").value(false));

        verify(profileService).getMyProfile(3);
        verify(currentUser, never()).requireCurrentUserId();
    }

    @Test
    void getMyProfile_ServiceThrowForbiddenAccess時映射403() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenThrow(new ForbiddenAccessException("此帳號沒有可用的員工資料"));

        mockMvc.perform(get("/api/employee/profile"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCESS"));
    }

    @Test
    void getMyProfile_回應不含敏感或內部欄位() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/employee/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.reviewNote").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    // === PUT /api/employee/profile ==================================================

    @Test
    void updateMyProfile_以requireCurrentUserId取得accountId並委派() throws Exception {
        ArgumentCaptor<EmployeeProfileUpdateRequest> captor =
                ArgumentCaptor.forClass(EmployeeProfileUpdateRequest.class);
        when(currentUser.requireCurrentUserId()).thenReturn(5);
        when(profileService.updateMyProfile(eq(5), captor.capture())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/employee/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { 
                                    "firstName": "小美",
                                    "lastName": "王",
                                    "phone": "0933333333" 
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getPhone()).isEqualTo("0933333333");
        verify(currentUser, never()).requireCurrentEmployeeId();
    }

    @Test
    void updateMyProfile_缺phone時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/employee/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }

    @Test
    void updateMyProfile_phone為空白時回400() throws Exception {
        mockMvc.perform(put("/api/employee/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "phone": "   " }
                                """))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }

    /**
     * 台灣手機格式（^09\d{8}$、固定 10 碼）：非 09 開頭／不足 10／超過 10／含非數字
     * 一律回 400 且不呼叫 Service。空白已由 {@link #updateMyProfile_phone為空白時回400()} 覆蓋。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "0812345678",            // 非 09 開頭
            "091234567",             // 不足 10 碼
            "09123456789",           // 超過 10 碼
            "123456789012345678901", // 遠超過長度上限
            "09abcdefgh",            // 含非數字
            "0912-345678"            // 含符號
    })
    void updateMyProfile_phone格式不符時回400(String badPhone) throws Exception {
        mockMvc.perform(put("/api/employee/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "小美", "lastName": "王", "phone": "%s" }
                                """.formatted(badPhone)))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }
}
