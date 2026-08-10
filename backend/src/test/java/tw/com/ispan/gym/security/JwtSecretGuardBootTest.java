package tw.com.ispan.gym.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 實際啟動一個最小 context，確認 guard 真的能擋下／放行，
 * 而不只是單元測試層級的方法呼叫。
 */
class JwtSecretGuardBootTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
            org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
            org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
    })
    @Import(JwtSecretGuard.class)
    static class MinimalApp {}

    private ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(MinimalApp.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run(args);
    }

    @Test
    void 使用預設secret且未指定profile時_應用程式啟動失敗() {
        assertThatThrownBy(() -> run("--app.jwt.secret=" + JwtSecretGuard.DEV_SECRET))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void 使用預設secret但為dev_profile時_應用程式正常啟動() {
        assertThatCode(() -> {
            try (var ctx = run(
                    "--app.jwt.secret=" + JwtSecretGuard.DEV_SECRET,
                    "--spring.profiles.active=dev")) {
                assertThat(ctx.getBean(JwtSecretGuard.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void 覆蓋secret後即使沒有profile也能啟動() {
        String custom = "c29tZS1vdGhlci1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaC1mb3ItaHMyNTY=";
        assertThatCode(() -> {
            try (var ctx = run("--app.jwt.secret=" + custom)) {
                assertThat(ctx.getBean(JwtSecretGuard.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
