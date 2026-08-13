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
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.repository.AccountRepository;
import tw.com.ispan.gym.repository.MemberRepository;
import tw.com.ispan.gym.repository.OauthAccountRepository;

/**
 * 登入身分一律先解析到 {@link Account}（email 或 OAuth 綁定皆屬帳號域），
 * 需要會員語意時再以 account_id 反查 member。
 */
@Component
@Profile("!dev")
public class UnconfiguredCurrentUserProvider implements CurrentUserProvider {
    private final AccountRepository accounts;
    private final MemberRepository members;
    private final OauthAccountRepository oauthAccounts;

    public UnconfiguredCurrentUserProvider(AccountRepository accounts, MemberRepository members,
            OauthAccountRepository oauthAccounts) {
        this.accounts = accounts;
        this.members = members;
        this.oauthAccounts = oauthAccounts;
    }

    /** 帳號 id：涵蓋員工／教練等沒有會員資料的帳號 */
    @Override
    public Integer requireCurrentUserId() {
        Account account = requireAccount(requireAuthentication());
        return account.getId();
    }

    /** 會員 id：member_id 與 account_id 已脫鉤，會員域模組一律用這個 */
    @Override
    public Integer requireCurrentMemberId() {
        Account account = requireAccount(requireAuthentication());
        Member member = members.findByAccountId(account.getId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("找不到登入資訊對應的會員帳號"));
        if (member.getDeletedAt() != null || member.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("此會員帳號目前無法使用");
        }
        return member.getId();
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AuthenticationCredentialsNotFoundException("請先登入後再執行此操作");
        }
        return authentication;
    }

    private Account requireAccount(Authentication authentication) {
        Account account = resolveAccount(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("找不到登入資訊對應的帳號"));
        if (account.getDeletedAt() != null) {
            throw new DisabledException("此帳號已停用");
        }
        return account;
    }

    private Optional<Account> resolveAccount(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oauth2User) {
            Optional<Account> bound = resolveByProviderBinding(authentication, oauth2User);
            if (bound.isPresent()) return bound;
        }
        String email = resolveEmail(authentication);
        return email == null ? Optional.empty() : accounts.findByEmailIgnoreCase(email);
    }

    /** 從各種登入方式的 principal 取出 email；取不到回 null */
    private String resolveEmail(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oauth2User) {
            String email = oauth2User.getAttribute("email");
            if (email != null && !email.isBlank()) return email;
        }
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? null : name;
    }

    /** 以 (provider, sub) 查 oauth_account 綁定表；sub 只在同一 provider 內唯一 */
    private Optional<Account> resolveByProviderBinding(Authentication authentication, OAuth2User oauth2User) {
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
        return oauthAccounts.findAccountByProviderAndSubject(provider, subject);
    }
}
