package com.wpowergym.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** TODO: replace with authenticated user from Spring Security/JWT. */
@Component
@Profile("dev")
public class DevCurrentUserProvider implements CurrentUserProvider {
    private final Long currentUserId;

    public DevCurrentUserProvider(@Value("${app.dev-current-user-id}") Long currentUserId) {
        this.currentUserId = currentUserId;
    }

    @Override
    public Long requireCurrentUserId() {
        return currentUserId;
    }
}
