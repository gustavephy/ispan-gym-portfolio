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
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.enums.UserType;
import tw.com.ispan.gym.repository.MemberRepository;

/**
 * 解析每個請求的 Authorization: Bearer …，驗簽通過就寫入 SecurityContext。
 * 沒帶 token 或 token 無效時一律放行，交由 SecurityConfig 的授權規則決定要不要擋。
 *
 * <p>目前只認 utype=MEMBER 的 token。member 與 employee 是兩張獨立的表、
 * id 各自從 1 開始，故驗證前必須先看 utype 決定查哪張表。
 *
 * <p>本類別發出的 ROLE_* 權限目前全部來自 member.role；接上 employee 登入時，
 * 要先讓這裡能依 employee.role 發出 ROLE_ADMIN，才可以移除 Member.role。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final MemberRepository members;

    public JwtAuthFilter(JwtUtil jwtUtil, MemberRepository members) {
        this.jwtUtil = jwtUtil;
        this.members = members;
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
        Integer subjectId;
        UserType userType;
        try {
            Claims claims = jwtUtil.parseClaims(token);
            subjectId = Integer.valueOf(claims.getSubject());
            userType = jwtUtil.extractUserType(token);
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // 驗簽失敗、過期、subject 非法或 utype 不合法：維持匿名，讓授權規則回 401
            logger.debug("JWT 解析失敗: " + ex.getMessage());
            return;
        }

        // subject 只是個數字，member 與 employee 的 id 空間各自獨立，
        // 必須先確認 utype 才知道要查哪張表；認錯表就會認錯人。
        if (userType != UserType.MEMBER) {
            // TODO 開放員工登入時，在此依 UserType.EMPLOYEE 分流查 EmployeeRepository，
            // 權限改用 employee.role（ROLE_TRAINER 另需查 trainer 明細表是否存在且未軟刪除）
            logger.debug("尚未支援的登入身分別: " + userType);
            return;
        }

        // 每次請求重查資料庫，帳號被停權時不必等 token 過期就會失效
        Member member = members.findById(subjectId).orElse(null);
        if (member == null || member.getStatus() != UserStatus.ACTIVE) {
            logger.debug("JWT 對應的會員不存在或未啟用: " + subjectId);
            return;
        }

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
        var authentication = new UsernamePasswordAuthenticationToken(member.getEmail(), null, authorities);
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
