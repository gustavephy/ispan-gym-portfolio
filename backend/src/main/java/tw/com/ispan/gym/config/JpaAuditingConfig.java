package tw.com.ispan.gym.config;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "taipeiDateTimeProvider")
public class JpaAuditingConfig {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Bean
    DateTimeProvider taipeiDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(TAIPEI));
    }
}
