package tw.com.ispan.gym.security;

import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.user.entity.User;
import tw.com.ispan.gym.user.entity.UserStatus;
import tw.com.ispan.gym.user.repository.UserRepository;

@Component
@Profile("!dev")
public class UnconfiguredCurrentUserProvider implements CurrentUserProvider {
    private final UserRepository users;

    public UnconfiguredCurrentUserProvider(UserRepository users) {
        this.users = users;
    }

    @Override
    public Long requireCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AuthenticationCredentialsNotFoundException("請先登入後再執行此操作");
        }

        User user = resolveUser(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("找不到登入資訊對應的會員帳號"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("此會員帳號目前無法使用");
        }
        return user.getId();
    }

    private Optional<User> resolveUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oauth2User) {
            String subject = oauth2User.getAttribute("sub");
            if (subject != null && !subject.isBlank()) {
                Optional<User> user = users.findByOauthSub(subject);
                if (user.isPresent()) return user;
            }
            String email = oauth2User.getAttribute("email");
            if (email != null && !email.isBlank()) return users.findByEmailIgnoreCase(email);
        }
        if (principal instanceof UserDetails userDetails) {
            return users.findByEmailIgnoreCase(userDetails.getUsername());
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? Optional.empty() : users.findByEmailIgnoreCase(name);
    }
}
