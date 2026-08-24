package tw.com.ispan.gym.controller.member;

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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.member.AdminMemberResponse;
import tw.com.ispan.gym.dto.member.AdminMemberSummaryResponse;
import tw.com.ispan.gym.dto.member.AdminMemberUpdateRequest;
import tw.com.ispan.gym.enums.member.Gender;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.InvalidRequestException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.coach.MemberCourseQuotaService;
import tw.com.ispan.gym.service.member.AdminMemberService;

/**
 * AdminMemberController 的路由、參數綁定、DTO 驗證、Service 委派與例外映射測試：
 * standalone MockMvc、AdminMemberService 全 mock，不套 Spring Security。
 *
 * <p>這支不驗權限——AdminMemberService 本身的商業邏輯（狀態轉換、悲觀鎖、排序白名單等）
 * 由 {@code AdminMemberServiceTest} 負責；URL 層與方法層授權分別由
 * {@code EndpointAuthorizationTest}（真牌 SecurityConfig，只能證明 URL 層）與
 * {@code AdminMemberMethodSecurityTest}（刻意放寬 URL 層，隔離驗證 @PreAuthorize）負責。
 * 三支測試合起來才涵蓋完整責任範圍，任何一支都不是為了取代另外兩支。
 */
class AdminMemberControllerTest {

    private final AdminMemberService adminMemberService = mock(AdminMemberService.class);
    // 額度查詢端點的行為由 MemberCourseQuotaService 自己的測試負責，這裡只需一個 mock
    // 滿足建構子，維持本測試「只驗 AdminMemberService 委派」的既有範圍
    private final MemberCourseQuotaService memberCourseQuotaService = mock(MemberCourseQuotaService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminMemberController(adminMemberService, memberCourseQuotaService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    // === GET /api/admin/members：參數綁定與傳遞 ====================================

    @Test
    void list_預設參數正確傳給Service() throws Exception {
        // 用 Page.empty() 而不是 Page.empty(Pageable) 會拿到 Pageable.unpaged()，
        // 它的 getOffset() 故意設計成丟 UnsupportedOperationException，Jackson 序列化
        // pageable.offset 時會直接炸成 500——這裡帶一個真正的 PageRequest 避開這個問題。
        when(adminMemberService.list(isNull(), isNull(), eq(0), eq(10), isNull(), isNull()))
                .thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0, 10)));

        mockMvc.perform(get("/api/admin/members"))
                .andExpect(status().isOk());

        verify(adminMemberService).list(isNull(), isNull(), eq(0), eq(10), isNull(), isNull());
    }

    @Test
    void list_自訂參數正確傳遞() throws Exception {
        when(adminMemberService.list(eq("small"), eq(UserStatus.ACTIVE), eq(2), eq(25), eq("name"), eq("desc")))
                .thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(2, 25)));

        mockMvc.perform(get("/api/admin/members")
                        .param("keyword", "small")
                        .param("status", "ACTIVE")
                        .param("page", "2")
                        .param("size", "25")
                        .param("sort", "name")
                        .param("direction", "desc"))
                .andExpect(status().isOk());

        verify(adminMemberService).list(eq("small"), eq(UserStatus.ACTIVE), eq(2), eq(25), eq("name"), eq("desc"));
    }

    @Test
    void list_回傳PageJSON含content與分頁metadata() throws Exception {
        AdminMemberSummaryResponse summary = new AdminMemberSummaryResponse(
                3, "王小美", "member@example.com", "0922222222",
                UserStatus.ACTIVE, LocalDateTime.of(2026, 1, 1, 0, 0));
        Page<AdminMemberSummaryResponse> page = new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1);
        when(adminMemberService.list(any(), any(), anyInt(), anyInt(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/admin/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].memberId").value(3))
                .andExpect(jsonPath("$.content[0].displayName").value("王小美"))
                .andExpect(jsonPath("$.content[0].email").value("member@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void list_status非法值回400且不呼叫Service() throws Exception {
        mockMvc.perform(get("/api/admin/members").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());

        verify(adminMemberService, never()).list(any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void list_ServiceThrowInvalidRequest時映射400() throws Exception {
        when(adminMemberService.list(any(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new InvalidRequestException("頁碼不可小於 0"));

        mockMvc.perform(get("/api/admin/members").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // === GET /api/admin/members/{memberId} =========================================

    @Test
    void getById_成功回傳資料() throws Exception {
        AdminMemberResponse response = new AdminMemberResponse(
                3, "member@example.com", "小美", "王", "0922222222",
                null, null, null, null, null, null, null, null, null,
                UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(adminMemberService.getById(3)).thenReturn(response);

        mockMvc.perform(get("/api/admin/members/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(3))
                .andExpect(jsonPath("$.email").value("member@example.com"));
    }

    @Test
    void getById_ServiceThrowResourceNotFound時映射404() throws Exception {
        when(adminMemberService.getById(3)).thenThrow(new ResourceNotFoundException("找不到此會員"));

        mockMvc.perform(get("/api/admin/members/3"))
                .andExpect(status().isNotFound());
    }

    // === PUT /api/admin/members/{memberId} =========================================

    @Test
    void update_有效body正確映射11個欄位並委派() throws Exception {
        ArgumentCaptor<AdminMemberUpdateRequest> captor = ArgumentCaptor.forClass(AdminMemberUpdateRequest.class);
        AdminMemberResponse response = new AdminMemberResponse(
                3, "member@example.com", "小明", "林", "0933333333",
                LocalDate.of(1990, 1, 1), Gender.F, "陳小華", "0244556677",
                "100", "台北市", "中正區", "忠孝東路一段1號", null,
                UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
        when(adminMemberService.update(eq(3), captor.capture())).thenReturn(response);

        mockMvc.perform(put("/api/admin/members/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "小明",
                                  "lastName": "林",
                                  "phone": "0933333333",
                                  "dob": "1990-01-01",
                                  "gender": "F",
                                  "emergencyContact": "陳小華",
                                  "emergencyPhone": "0244556677",
                                  "zipCode": "100",
                                  "city": "台北市",
                                  "district": "中正區",
                                  "address": "忠孝東路一段1號"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("小明"));

        AdminMemberUpdateRequest captured = captor.getValue();
        assertThat(captured.getFirstName()).isEqualTo("小明");
        assertThat(captured.getLastName()).isEqualTo("林");
        assertThat(captured.getPhone()).isEqualTo("0933333333");
        assertThat(captured.getDob()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(captured.getGender()).isEqualTo(Gender.F);
        assertThat(captured.getEmergencyContact()).isEqualTo("陳小華");
        assertThat(captured.getEmergencyPhone()).isEqualTo("0244556677");
        assertThat(captured.getZipCode()).isEqualTo("100");
        assertThat(captured.getCity()).isEqualTo("台北市");
        assertThat(captured.getDistrict()).isEqualTo("中正區");
        assertThat(captured.getAddress()).isEqualTo("忠孝東路一段1號");
    }

    @Test
    void update_缺firstName時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/members/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lastName": "林", "phone": "0933333333" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminMemberService, never()).update(any(), any());
    }

    @Test
    void update_缺lastName時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/members/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "小明", "phone": "0933333333" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminMemberService, never()).update(any(), any());
    }

    @Test
    void update_缺phone時回400且不呼叫Service() throws Exception {
        mockMvc.perform(put("/api/admin/members/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "firstName": "小明", "lastName": "林" }
                                """))
                .andExpect(status().isBadRequest());

        verify(adminMemberService, never()).update(any(), any());
    }

    // === POST /api/admin/members/{memberId}/suspend ／ /activate ===================

    @Test
    void suspend_正確傳入memberId() throws Exception {
        mockMvc.perform(post("/api/admin/members/3/suspend"))
                .andExpect(status().isOk());

        verify(adminMemberService).suspend(3);
    }

    @Test
    void suspend_ServiceThrowBusinessRule時映射409() throws Exception {
        doThrow(new BusinessRuleException("此會員已是停權狀態")).when(adminMemberService).suspend(3);

        mockMvc.perform(post("/api/admin/members/3/suspend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void activate_正確傳入memberId() throws Exception {
        mockMvc.perform(post("/api/admin/members/3/activate"))
                .andExpect(status().isOk());

        verify(adminMemberService).activate(3);
    }

    @Test
    void activate_ServiceThrowResourceNotFound時映射404() throws Exception {
        doThrow(new ResourceNotFoundException("找不到此會員")).when(adminMemberService).activate(3);

        mockMvc.perform(post("/api/admin/members/3/activate"))
                .andExpect(status().isNotFound());
    }
}
