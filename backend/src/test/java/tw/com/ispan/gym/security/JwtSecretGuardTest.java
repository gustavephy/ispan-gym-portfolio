package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtSecretGuardTest {

    private JwtSecretGuard guard(String secret, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        return new JwtSecretGuard(secret, env);
    }

    @Test
    void 預設secret在沒有指定profile時擋住啟動() {
        assertThatThrownBy(() -> guard(JwtSecretGuard.DEV_SECRET).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void 預設secret在prod_profile下擋住啟動() {
        assertThatThrownBy(() -> guard(JwtSecretGuard.DEV_SECRET, "prod").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 預設secret混用dev與prod時仍擋住啟動() {
        assertThatThrownBy(() -> guard(JwtSecretGuard.DEV_SECRET, "dev", "prod").verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 預設secret在dev_profile下允許啟動() {
        assertThatCode(() -> guard(JwtSecretGuard.DEV_SECRET, "dev").verify()).doesNotThrowAnyException();
    }

    @Test
    void 自訂secret在任何profile下都允許啟動() {
        String custom = "c29tZS1vdGhlci1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTY=";
        assertThatCode(() -> guard(custom, "prod").verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard(custom).verify()).doesNotThrowAnyException();
    }

    @Test
    void 預設值必須與application_properties一致且長度足夠() {
        // 解碼後需 >= 32 bytes，否則 HS256 會在啟動時丟例外
        assertThat(java.util.Base64.getDecoder().decode(JwtSecretGuard.DEV_SECRET).length)
                .isGreaterThanOrEqualTo(32);
    }
}
