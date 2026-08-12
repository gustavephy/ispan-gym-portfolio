package tw.com.ispan.gym.security;

import java.util.Locale;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.memberdetails.memberDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.member.OAuth2member;
import org.springframework.stereotype.Component;

import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.MemberStatus;
import tw.com.ispan.gym.enums.OAuthProvider;
import tw.com.ispan.gym.repository.MemberOauthAccountRepository;
import tw.com.ispan.gym.repository.MemberRepository;

@Component
@Profile("!dev")
public class UnconfiguredCurrentmemberProvider implements CurrentmemberProvider {
    private final MemberRepository members;
    private final MemberOauthAccountRepository oauthAccounts;

    public UnconfiguredCurrentmemberProvider(MemberRepository members, MemberOauthAccountRepository oauthAccounts) {
        this.members = members;
        this.oauthAccounts = oauthAccounts;
    }

    @Override
    public Integer requireCurrentmemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousmember".equals(authentication.getPrincipal())) {
            throw new AuthenticationCredentialsNotFoundException("請先登入後再執行此操作");
        }

        Member member = resolvemember(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("找不到登入資訊對應的會員帳號"));
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new DisabledException("此會員帳號目前無法使用");
        }
        return member.getMemberId();
    }

    private Optional<Member> resolvemember(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2member oauth2member) {
            Optional<Member> bound = resolveByProviderBinding(authentication, oauth2member);
            if (bound.isPresent()) return bound;
            String email = oauth2member.getAttribute("email");
            if (email != null && !email.isBlank()) return members.findByEmailIgnoreCase(email);
        }
        if (principal instanceof memberDetails memberDetails) {
            return members.findByEmailIgnoreCase(memberDetails.getmembername());
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? Optional.empty() : members.findByEmailIgnoreCase(name);
    }

    /** 以 (provider, sub) 查 member_oauth_accounts 綁定表；sub 只在同一 provider 內唯一 */
    private Optional<Member> resolveByProviderBinding(Authentication authentication, OAuth2member oauth2member) {
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
        String subject = oauth2member.getAttribute("sub");
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return oauthAccounts.findMemberByProviderAndSubject(provider, subject);
    }
}
