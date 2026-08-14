package tw.com.ispan.gym.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.repository.account.AccountRepository;

/**
 * 解析每個請求的 Authorization: Bearer …，驗簽通過就寫入 SecurityContext。
 * 沒帶 token 或 token 無效時一律放行，交由 SecurityConfig 的授權規則決定要不要擋。
 *
 * <p>JWT 的 subject 是 account_id。member 與 employee 各有獨立流水號主鍵，
 * 一律以 account_id 反查各身分表，不把 account_id 當成 member／employee id 用。
 *
 * <p>權限展開規則見 {@link AccountAuthorityResolver}（與登入回應的 roles 欄位共用）。
 * 一個帳號可同時持有會員與員工身分。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final AccountRepository accounts;
    private final AccountAuthorityResolver authorityResolver;

    public JwtAuthFilter(JwtUtil jwtUtil, AccountRepository accounts, AccountAuthorityResolver authorityResolver) {
        this.jwtUtil = jwtUtil;
        this.accounts = accounts;
        this.authorityResolver = authorityResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        Integer accountId;
        try {
            accountId = Integer.valueOf(jwtUtil.parseClaims(token).getSubject());
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // 驗簽失敗、過期或 subject 非法：維持匿名，讓授權規則回 401
            logger.debug("JWT 解析失敗: " + ex.getMessage());
            return;
        }

        // 每次請求重查資料庫，帳號被停權或軟刪除時不必等 token 過期就會失效
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null || account.getDeletedAt() != null) {
            logger.debug("JWT 對應的帳號不存在或已刪除: " + accountId);
            return;
        }

        List<SimpleGrantedAuthority> authorities = authorityResolver.resolve(account);
        if (authorities.isEmpty()) {
            logger.debug("帳號沒有任何已啟用的身分: " + accountId);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(account.getEmail(), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** 取出 Authorization: Bearer … 的 token，格式不符回 null */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
