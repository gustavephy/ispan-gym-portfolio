package tw.com.ispan.gym.controller.trainer;

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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.trainer.TrainerProfileResponse;
import tw.com.ispan.gym.dto.trainer.TrainerProfileUpdateRequest;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.trainer.TrainerProfileService;

/**
 * TrainerProfileController 的路由、request validation、Service 委派與例外映射測試：
 * standalone MockMvc，TrainerProfileService／CurrentUserProvider 全 mock，不套 Spring Security。
 * 風格比照 EmployeeProfileControllerTest。
 *
 * <p>這支不驗權限——TrainerProfileService 本身的商業邏輯由 TrainerProfileServiceTest
 * 負責；URL 層與方法層授權分別由 EndpointAuthorizationTest（真牌 SecurityConfig，只能
 * 證明 URL 層）與 TrainerMethodSecurityTest（隔離驗證 @PreAuthorize）負責。
 */
class TrainerProfileControllerTest {

    private final TrainerProfileService profileService = mock(TrainerProfileService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TrainerProfileController(profileService, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private TrainerProfileResponse sampleResponse() {
        return new TrainerProfileResponse(7, 3, "重量訓練", "五年健身房授課經驗",
                "專注於初學者的肌力養成。", "https://example.com/trainer/7.jpg", TrainerStatus.ACTIVE);
    }

    private TrainerProfileUpdateRequest validRequest() {
        TrainerProfileUpdateRequest request = new TrainerProfileUpdateRequest();
        request.setSpecialty("重量訓練");
        request.setExperience("五年健身房授課經驗");
        request.setBio("專注於初學者的肌力養成。");
        request.setProfileImageUrl("https://example.com/trainer/7.jpg");
        return request;
    }

    // === GET /api/trainer/profile ====================================================

    @Test
    void getMyProfile_以requireCurrentEmployeeId取得employeeId並委派() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trainer/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerId").value(7))
                .andExpect(jsonPath("$.specialty").value("重量訓練"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(profileService).getMyProfile(3);
        verify(currentUser, never()).requireCurrentUserId();
    }

    @Test
    void getMyProfile_ServiceThrowForbiddenAccess時映射403() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenThrow(new ForbiddenAccessException("此帳號沒有可用的教練資料"));

        mockMvc.perform(get("/api/trainer/profile"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCESS"));
    }

    @Test
    void getMyProfile_回應不含一般個資或內部欄位() throws Exception {
        when(currentUser.requireCurrentEmployeeId()).thenReturn(3);
        when(profileService.getMyProfile(3)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trainer/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    // === PUT /api/trainer/profile ====================================================

    @Test
    void updateMyProfile_以requireCurrentUserId取得accountId並委派() throws Exception {
        ArgumentCaptor<TrainerProfileUpdateRequest> captor =
                ArgumentCaptor.forClass(TrainerProfileUpdateRequest.class);
        when(currentUser.requireCurrentUserId()).thenReturn(5);
        when(profileService.updateMyProfile(eq(5), captor.capture())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "experience": "五年健身房授課經驗",
                                  "bio": "專注於初學者的肌力養成。", "profileImageUrl": "https://example.com/trainer/7.jpg" }
                                """))
                .andExpect(status().isOk());

        assertThat(captor.getValue().getSpecialty()).isEqualTo("重量訓練");
        verify(currentUser, never()).requireCurrentEmployeeId();
    }

    @Test
    void updateMyProfile_缺specialty時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "experience": "五年", "bio": "自我介紹" }
                                """))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }

    @Test
    void updateMyProfile_缺experience時回400() throws Exception {
        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "bio": "自我介紹" }
                                """))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }

    @Test
    void updateMyProfile_缺bio時回400() throws Exception {
        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "experience": "五年" }
                                """))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }

    @Test
    void updateMyProfile_profileImageUrl未帶時仍合法() throws Exception {
        when(currentUser.requireCurrentUserId()).thenReturn(5);
        when(profileService.updateMyProfile(eq(5), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "重量訓練", "experience": "五年", "bio": "自我介紹" }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void updateMyProfile_specialty超過255字元時回400() throws Exception {
        String tooLong = "重".repeat(256);
        mockMvc.perform(put("/api/trainer/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "specialty": "%s", "experience": "五年", "bio": "自我介紹" }
                                """.formatted(tooLong)))
                .andExpect(status().isBadRequest());

        verify(profileService, never()).updateMyProfile(any(), any());
    }
}
