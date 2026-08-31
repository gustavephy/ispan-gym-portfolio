package tw.com.ispan.gym.controller.employee;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tw.com.ispan.gym.dto.employee.EmployeeRegisterResponse;
import tw.com.ispan.gym.exception.GlobalExceptionHandler;
import tw.com.ispan.gym.service.employee.EmployeeRegisterService;

/**
 * EmployeeRegisterController 的 request validation 測試：standalone MockMvc，
 * EmployeeRegisterService 全 mock。專門釘住 register／reapply 兩個端點的台灣手機格式
 * （^09\d{8}$、固定 10 碼）——不能繞過前端直接送非法號碼進 PENDING_APPROVAL。
 */
class EmployeeRegisterControllerTest {

    private final EmployeeRegisterService registerService = mock(EmployeeRegisterService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new EmployeeRegisterController(registerService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private static final String VALID_PASSWORD = "Abcd1234";

    private String registerBody(String phone) {
        return """
                {
                  "firstName": "小明",
                  "lastName": "王",
                  "email": "applicant@example.com",
                  "phone": "%s",
                  "password": "%s",
                  "hireDate": "2024-01-01"
                }
                """.formatted(phone, VALID_PASSWORD);
    }

    private String reapplyBody(String phone) {
        return """
                {
                  "email": "applicant@example.com",
                  "password": "%s",
                  "firstName": "小明",
                  "lastName": "王",
                  "phone": "%s",
                  "hireDate": "2024-01-01"
                }
                """.formatted(VALID_PASSWORD, phone);
    }

    @Test
    void register_合法台灣手機號碼放行並委派() throws Exception {
        when(registerService.register(any())).thenReturn(new EmployeeRegisterResponse("applicant@example.com", "PENDING_APPROVAL"));

        mockMvc.perform(post("/api/employees/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("0912345678")))
                .andExpect(status().isCreated());

        verify(registerService).register(any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "0812345678", "091234567", "09123456789", "123456789012345678901", "09abcdefgh", "   " })
    void register_phone格式不符時回400且不呼叫Service(String badPhone) throws Exception {
        mockMvc.perform(post("/api/employees/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(badPhone)))
                .andExpect(status().isBadRequest());

        verify(registerService, never()).register(any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "0812345678", "091234567", "09123456789", "123456789012345678901", "09abcdefgh", "   " })
    void reapply_phone格式不符時回400且不呼叫Service(String badPhone) throws Exception {
        mockMvc.perform(post("/api/employees/reapply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reapplyBody(badPhone)))
                .andExpect(status().isBadRequest());

        verify(registerService, never()).reapply(any());
    }
}
