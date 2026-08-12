package tw.com.ispan.gym.security;

import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.enums.OAuthProvider;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.MemberOauthAccountRepository;
import tw.com.ispan.gym.repository.MemberRepository;

@Component
@Profile("!dev")
public class UnconfiguredCurrentUserProvider implements CurrentUserProvider {
    private final MemberRepository users;
    private final MemberOauthAccountRepository oauthAccounts;

    public UnconfiguredCurrentUserProvider(MemberRepository users, MemberOauthAccountRepository oauthAccounts) {
        this.users = users;
        this.oauthAccounts = oauthAccounts;
    }

    @Override
    public Integer requireCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AuthenticationCredentialsNotFoundException("請先登入後再執行此操作");
        }

        Member user = resolveUser(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("找不到登入資訊對應的會員帳號"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("此會員帳號目前無法使用");
        }
        return user.getMemberId();
    }

    private Optional<Member> resolveUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oauth2User) {
            Optional<Member> bound = resolveByProviderBinding(authentication, oauth2User);
            if (bound.isPresent()) return bound;
            String email = oauth2User.getAttribute("email");
            if (email != null && !email.isBlank()) return users.findByEmailIgnoreCase(email);
        }
        if (principal instanceof UserDetails userDetails) {
            return users.findByEmailIgnoreCase(userDetails.getUsername());
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? Optional.empty() : users.findByEmailIgnoreCase(name);
    }

    /** 以 (provider, sub) 查 user_oauth_accounts 綁定表；sub 只在同一 provider 內唯一 */
    private Optional<Member> resolveByProviderBinding(Authentication authentication, OAuth2User oauth2User) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return Optional.empty();
        }
        OAuthProvider provider;
        try {
            provider = OAuthProvider.valueOf(
                    token.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        String subject = oauth2User.getAttribute("sub");
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return oauthAccounts.findMemberByProviderAndSubject(provider, subject);
    }
}
