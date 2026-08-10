package tw.com.ispan.gym.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 防止本機開發用的預設 JWT secret 被帶到正式環境。
 * 只有 dev / test profile 允許使用預設值，其餘情況一律讓應用程式啟動失敗。
 */
@Component
public class JwtSecretGuard {

    /** 與 application.properties 的預設值相同，比對用 */
    static final String DEV_SECRET =
            "aXNwYW4tZ3ltLWxvY2FsLWRldi1vbmx5LXNlY3JldC1kby1ub3QtdXNlLWluLXByb2Qh";

    private static final java.util.Set<String> LOCAL_PROFILES = java.util.Set.of("dev", "test", "local");

    private final String secret;
    private final Environment environment;

    public JwtSecretGuard(@Value("${app.jwt.secret}") String secret, Environment environment) {
        this.secret = secret;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        if (!DEV_SECRET.equals(secret)) {
            return;
        }

        String[] active = environment.getActiveProfiles();
        boolean localOnly = active.length > 0
                && java.util.Arrays.stream(active).allMatch(LOCAL_PROFILES::contains);

        if (!localOnly) {
            throw new IllegalStateException("""
                    偵測到正在使用本機開發用的預設 app.jwt.secret。
                    這組密鑰已經進版控，任何人都能用它偽造任意帳號（含 ADMIN）的 token。
                    請設定 JWT_SECRET 環境變數，例如：
                      PowerShell> $env:JWT_SECRET=[Convert]::ToBase64String((1..32|%{Get-Random -Max 256}))
                    或將 spring.profiles.active 設為 dev / test 以明確表示這是本機環境。""");
        }
    }
}
