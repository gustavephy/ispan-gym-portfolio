package com.wpowergym.security;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!dev")
public class UnconfiguredCurrentUserProvider implements CurrentUserProvider {
    @Override
    public Long requireCurrentUserId() {
        throw new IllegalStateException("登入/JWT 模組尚未提供 CurrentUserProvider 實作");
    }
}
