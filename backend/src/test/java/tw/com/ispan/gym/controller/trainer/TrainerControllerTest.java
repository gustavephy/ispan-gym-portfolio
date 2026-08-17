package tw.com.ispan.gym.controller.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.trainer.TrainerCreateRequest;
import tw.com.ispan.gym.dto.trainer.TrainerResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.trainer.Trainer;
import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;
import tw.com.ispan.gym.enums.trainer.TrainerStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.service.trainer.TrainerService;

/**
 * TrainerController 的路由與回應格式測試：standalone MockMvc、service 全 mock。
 *
 * <p>重點是公開 DTO 的防洩漏——刻意讓 mock 回傳「由完整 Employee 實體投影出來的」
 * TrainerResponse（該 Employee 帶著 email／電話／角色／審核備註等內部資料），
 * 再斷言序列化後的 JSON 只有 6 個公開欄位。這樣測到的是真實的投影＋序列化結果，
 * 而不只是 Java 類別上有哪些欄位。
 *
 * <p>權限（ADMIN 才能建立、GET 匿名可讀）不在這支測試範圍——standalone MockMvc
 * 不套用 Spring Security，那部分由 {@code EndpointAuthorizationTest} 以真實
 * SecurityConfig 驗證。
 */
class TrainerControllerTest {

    private final TrainerService trainerService = mock(TrainerService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TrainerController(trainerService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** 帶滿內部資料的員工，用來確認這些欄位不會流進公開回應 */
    private static Employee employeeWithPrivateData() {
        Account account = new Account();
        account.setId(2);
        account.setEmail("coach@example.com");
        account.setPasswordHash("$2a$10$notarealhash");

        Employee reviewer = new Employee();
        reviewer.setId(99);

        Employee employee = new Employee();
        employee.setId(22);
        employee.setAccount(account);
        employee.setFirstName("小華");
        employee.setLastName("陳");
        employee.setPhone("0911111111");
        employee.setRole(EmployeeRole.EMPLOYEE);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setHireDate(LocalDate.of(2024, 3, 1));
        employee.setReviewedBy(reviewer);
        employee.setReviewedAt(LocalDateTime.of(2024, 3, 2, 9, 0));
        employee.setReviewNote("面試通過");
        return employee;
    }

    private static TrainerResponse projected(Integer trainerId, TrainerStatus status) {
        Trainer trainer = new Trainer();
        trainer.setId(trainerId);
        trainer.setEmployee(employeeWithPrivateData());
        trainer.setSpecialty("重量訓練");
        trainer.setExperience("五年健身房授課經驗");
        trainer.setBio("專注於初學者的肌力養成。");
        trainer.setStatus(status);
        return TrainerResponse.from(trainer);
    }

    // === POST /api/employees/{employeeId}/trainer ================================

    @Test
    void 建立成功回201與trainerId() throws Exception {
        when(trainerService.create(eq(22), any(TrainerCreateRequest.class)))
                .thenReturn(projected(7, TrainerStatus.ACTIVE));

        mockMvc.perform(post("/api/employees/22/trainer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "specialty": "重量訓練",
                          "experience": "五年健身房授課經驗",
                          "bio": "專注於初學者的肌力養成。"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trainerId").value(7))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 建立時缺少必填欄位回400() throws Exception {
        mockMvc.perform(post("/api/employees/22/trainer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "specialty": "",
                          "experience": "五年健身房授課經驗",
                          "bio": "專注於初學者的肌力養成。"
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 建立時員工已是教練回409() throws Exception {
        when(trainerService.create(eq(22), any(TrainerCreateRequest.class)))
                .thenThrow(new BusinessRuleException("此員工已經是教練"));

        mockMvc.perform(post("/api/employees/22/trainer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "specialty": "重量訓練",
                          "experience": "五年健身房授課經驗",
                          "bio": "專注於初學者的肌力養成。"
                        }
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void 建立時員工不存在回404() throws Exception {
        when(trainerService.create(eq(99), any(TrainerCreateRequest.class)))
                .thenThrow(new ResourceNotFoundException("找不到此員工"));

        mockMvc.perform(post("/api/employees/99/trainer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "specialty": "重量訓練",
                          "experience": "五年健身房授課經驗",
                          "bio": "專注於初學者的肌力養成。"
                        }
                        """))
                .andExpect(status().isNotFound());
    }

    // === GET /api/trainers =======================================================

    @Test
    void 列表回200且ACTIVE與UNAVAILABLE都看得到() throws Exception {
        when(trainerService.listPublic()).thenReturn(List.of(
                projected(7, TrainerStatus.ACTIVE),
                projected(8, TrainerStatus.UNAVAILABLE)));

        mockMvc.perform(get("/api/trainers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].status").value("UNAVAILABLE"));
    }

    @Test
    void 沒有教練時列表回200空陣列() throws Exception {
        when(trainerService.listPublic()).thenReturn(List.of());

        mockMvc.perform(get("/api/trainers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // === GET /api/trainers/{trainerId} ===========================================

    @Test
    void 單筆查詢回200與公開欄位() throws Exception {
        when(trainerService.getById(7)).thenReturn(projected(7, TrainerStatus.ACTIVE));

        mockMvc.perform(get("/api/trainers/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainerId").value(7))
                .andExpect(jsonPath("$.displayName").value("陳小華"))
                .andExpect(jsonPath("$.specialty").value("重量訓練"))
                .andExpect(jsonPath("$.experience").value("五年健身房授課經驗"))
                .andExpect(jsonPath("$.bio").value("專注於初學者的肌力養成。"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 單筆查詢不存在或已軟刪除回404() throws Exception {
        when(trainerService.getById(7)).thenThrow(new ResourceNotFoundException("找不到此教練"));

        mockMvc.perform(get("/api/trainers/7"))
                .andExpect(status().isNotFound());
    }

    // === 公開 DTO 防洩漏 ==========================================================

    @Test
    void 公開JSON只有六個欄位不含員工內部資料() throws Exception {
        when(trainerService.getById(7)).thenReturn(projected(7, TrainerStatus.ACTIVE));

        mockMvc.perform(get("/api/trainers/7"))
                .andExpect(status().isOk())
                // 白名單：多一個欄位就會失敗，之後有人往 DTO 加東西會被這條擋下
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.trainerId").exists())
                .andExpect(jsonPath("$.displayName").exists())
                .andExpect(jsonPath("$.specialty").exists())
                .andExpect(jsonPath("$.experience").exists())
                .andExpect(jsonPath("$.bio").exists())
                .andExpect(jsonPath("$.status").exists())
                // 黑名單：逐一點名確認個資、角色與審核欄位都沒混進來
                .andExpect(jsonPath("$.employeeId").doesNotExist())
                .andExpect(jsonPath("$.employee").doesNotExist())
                .andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.birthDate").doesNotExist())
                .andExpect(jsonPath("$.dob").doesNotExist())
                .andExpect(jsonPath("$.emergencyContact").doesNotExist())
                .andExpect(jsonPath("$.emergencyPhone").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.hireDate").doesNotExist())
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.reviewNote").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    /** 上面用 jsonPath 逐欄斷言，這裡再直接掃原始字串，連巢狀或改名後的欄位都不放過 */
    @Test
    void 公開JSON原始字串不含任何員工敏感值() throws Exception {
        when(trainerService.listPublic()).thenReturn(List.of(projected(7, TrainerStatus.ACTIVE)));

        String json = mockMvc.perform(get("/api/trainers"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json)
                .doesNotContain("coach@example.com")
                .doesNotContain("0911111111")
                .doesNotContain("notarealhash")
                .doesNotContain("面試通過")
                .doesNotContain("2024-03-01")
                .doesNotContain("EMPLOYEE");
    }
}
