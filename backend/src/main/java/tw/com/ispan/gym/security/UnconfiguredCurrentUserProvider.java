package tw.com.ispan.gym.security;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!dev")
public class UnconfiguredCurrentUserProvider implements CurrentUserProvider {
    @Override
    public Long requireCurrentUserId() {
        throw new IllegalStateException("?餃/JWT 璅∠?撠?? CurrentUserProvider 撖虫?");
    }
}

