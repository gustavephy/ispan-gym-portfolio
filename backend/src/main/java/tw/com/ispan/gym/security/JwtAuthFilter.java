package tw.com.ispan.gym.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.employee.Employee;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.AccountType;
import tw.com.ispan.gym.enums.EmployeeStatus;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.AccountRepository;
import tw.com.ispan.gym.repository.EmployeeRepository;
import tw.com.ispan.gym.repository.MemberRepository;

/**
 * 解析每個請求的 Authorization: Bearer …，驗簽通過就寫入 SecurityContext。
 * 沒帶 token 或 token 無效時一律放行，交由 SecurityConfig 的授權規則決定要不要擋。
 *
 * <p>JWT 的 subject 是 account_id。member 與 employee 各有獨立流水號主鍵，
 * 一律以 account_id 反查各身分表，不把 account_id 當成 member／employee id 用。
 *
 * <p>權限由 account.roles 展開：具 MEMBER 身分且會員狀態 ACTIVE → ROLE_MEMBER；
 * 具 EMPLOYEE 身分且員工狀態 ACTIVE → ROLE_ADMIN／ROLE_TRAINER（依 employee.role）。
 * 一個帳號可同時持有兩種身分。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmployeeRepository employees;

    public JwtAuthFilter(JwtUtil jwtUtil, AccountRepository accounts, MemberRepository members,
            EmployeeRepository employees) {
        this.jwtUtil = jwtUtil;
        this.accounts = accounts;
        this.members = members;
        this.employees = employees;
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

        List<SimpleGrantedAuthority> authorities = resolveAuthorities(account);
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

    /** 依 account.roles 展開權限；未啟用的身分不授予任何權限 */
    private List<SimpleGrantedAuthority> resolveAuthorities(Account account) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        if (account.getRoles().contains(AccountType.MEMBER)) {
            Member member = members.findByAccountId(account.getId()).orElse(null);
            if (member != null && member.getDeletedAt() == null && member.getStatus() == UserStatus.ACTIVE) {
                authorities.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
            }
        }

        if (account.getRoles().contains(AccountType.EMPLOYEE)) {
            Employee employee = employees.findByAccountId(account.getId()).orElse(null);
            if (employee != null && employee.getDeletedAt() == null
                    && employee.getStatus() == EmployeeStatus.ACTIVE) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + employee.getRole().name()));
            }
        }
        return authorities;
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
