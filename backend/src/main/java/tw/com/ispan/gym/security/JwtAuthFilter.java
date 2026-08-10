package tw.com.ispan.gym.security;

import io.jsonwebtoken.Claims;
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
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.UserRepository;

/**
 * 解析每個請求的 Authorization: Bearer …，驗簽通過就寫入 SecurityContext。
 * 沒帶 token 或 token 無效時一律放行，交由 SecurityConfig 的授權規則決定要不要擋。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserRepository users;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository users) {
        this.jwtUtil = jwtUtil;
        this.users = users;
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
        Claims claims;
        Integer userId;
        try {
            claims = jwtUtil.parseClaims(token);
            userId = Integer.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // 驗簽失敗、過期或 subject 非法：維持匿名，讓授權規則回 401
            logger.debug("JWT 解析失敗: " + ex.getMessage());
            return;
        }

        // 每次請求重查資料庫，帳號被停權時不必等 token 過期就會失效
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            logger.debug("JWT 對應的會員不存在或未啟用: " + userId);
            return;
        }

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        var authentication = new UsernamePasswordAuthenticationToken(user.getEmail(), null, authorities);
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
