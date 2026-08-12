package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.mockito.Mockito;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.Role;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.enums.UserType;
import tw.com.ispan.gym.repository.MemberRepository;

/**
 * 驗證 JwtUtil / JwtAuthFilter / SecurityConfig 三者串起來的行為。
 * 不連資料庫，MemberRepository 以 mock 取代。
 */
@SpringBootTest(
        classes = {JwtSecuritySmokeTest.TestApp.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTYtc2lnbmluZyEh",
        "app.jwt.issuer=ispan-gym",
        "app.jwt.access-token-ttl=PT2H"
})
class JwtSecuritySmokeTest {

    /**
     * 不做 component scan，只挑要測的 bean，避免把需要 DataSource 的
     * SchemaFixupRunner 等一併帶進來。
     */
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    @Import({
        TestBeans.class,
        ProbeController.class,
        JwtUtil.class,
        JwtAuthFilter.class,
        ApiErrorWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        tw.com.ispan.gym.config.SecurityConfig.class
    })
    static class TestApp {}

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MemberRepository userRepository() {
            return Mockito.mock(MemberRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.MemberOauthAccountRepository userOauthAccountRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.MemberOauthAccountRepository.class);
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/orders")
        String secured() {
            return "ok";
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired MemberRepository users;

    private Member activeUser() {
        Member u = new Member();
        u.setMemberId(7);
        u.setEmail("member@example.com");
        u.setRole(Role.MEMBER);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    @Test
    void 無token時受保護端點回401() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void 帶有效token時放行並解析出會員() throws Exception {
        Member user = activeUser();
        Mockito.when(users.findById(7)).thenReturn(Optional.of(user));

        String token = jwtUtil.generateToken(user);
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(7);
        assertThat(jwtUtil.extractRole(token)).isEqualTo("MEMBER");
        assertThat(jwtUtil.extractUserType(token)).isEqualTo(UserType.MEMBER);

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void utype為EMPLOYEE的token不會被當成同號會員() throws Exception {
        // employee 與 member 的 id 空間各自獨立：employee_id=7 不是 member_id=7 那個人。
        // 員工登入尚未開放，這種 token 必須整個拒絕，不能落到 member 查詢。
        Member member = activeUser();
        Mockito.when(users.findById(7)).thenReturn(Optional.of(member));

        String employeeToken = jwtUtil.generateToken(
                7, Map.of(JwtUtil.CLAIM_EMAIL, "staff@example.com",
                        JwtUtil.CLAIM_USER_TYPE, UserType.EMPLOYEE.name()));

        assertThat(jwtUtil.extractUserType(employeeToken)).isEqualTo(UserType.EMPLOYEE);
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 缺utype的舊token仍視為會員() throws Exception {
        Member member = activeUser();
        Mockito.when(users.findById(7)).thenReturn(Optional.of(member));

        String legacyToken = jwtUtil.generateToken(7, Map.of(JwtUtil.CLAIM_EMAIL, member.getEmail()));

        assertThat(jwtUtil.extractUserType(legacyToken)).isEqualTo(UserType.MEMBER);
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isOk());
    }

    @Test
    void token被竄改時回401() throws Exception {
        String token = jwtUtil.generateToken(activeUser());
        // 直接改掉 payload 段第一個字元，簽章便對不上
        int firstDot = token.indexOf('.');
        char[] chars = token.toCharArray();
        chars[firstDot + 1] = chars[firstDot + 1] == 'e' ? 'f' : 'e';
        String tampered = new String(chars);

        assertThat(jwtUtil.isValid(tampered)).isFalse();
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 會員被停權時即使token有效也回401() throws Exception {
        Member user = activeUser();
        String token = jwtUtil.generateToken(user);

        user.setStatus(UserStatus.SUSPENDED);
        Mockito.when(users.findById(7)).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
