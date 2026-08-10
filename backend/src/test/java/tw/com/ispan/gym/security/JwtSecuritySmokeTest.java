package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.enums.Role;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.UserRepository;

/**
 * 驗證 JwtUtil / JwtAuthFilter / SecurityConfig 三者串起來的行為。
 * 不連資料庫，UserRepository 以 mock 取代。
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
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean
        @Primary
        tw.com.ispan.gym.repository.UserOauthAccountRepository userOauthAccountRepository() {
            return Mockito.mock(tw.com.ispan.gym.repository.UserOauthAccountRepository.class);
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
    @Autowired UserRepository users;

    private User activeUser() {
        User u = new User();
        u.setId(7);
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
        User user = activeUser();
        Mockito.when(users.findById(7)).thenReturn(Optional.of(user));

        String token = jwtUtil.generateToken(user);
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(7);
        assertThat(jwtUtil.extractRole(token)).isEqualTo("MEMBER");

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
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
        User user = activeUser();
        String token = jwtUtil.generateToken(user);

        user.setStatus(UserStatus.SUSPENDED);
        Mockito.when(users.findById(7)).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
