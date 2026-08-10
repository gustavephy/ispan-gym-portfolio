package tw.com.ispan.gym.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tw.com.ispan.gym.entity.User;

/**
 * 簽發與驗證 JWT。
 * 密鑰以 Base64 設定於 app.jwt.secret，長度需至少 256 bits（HS256）。
 */
@Component
public class JwtUtil {

    /** 放 email 的自訂 claim；subject 固定放 user id */
    public static final String CLAIM_EMAIL = "email";
    /** 放角色的自訂 claim，值為 Role enum 名稱（MEMBER / TRAINER / ADMIN） */
    public static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final String issuer;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl:PT2H}") Duration accessTokenTtl,
            @Value("${app.jwt.issuer:ispan-gym}") String issuer) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenTtl = accessTokenTtl;
        this.issuer = issuer;
    }

    /** 依會員簽發 access token，subject 為 user id */
    public String generateToken(User user) {
        return generateToken(
                user.getId(),
                Map.of(
                        CLAIM_EMAIL, user.getEmail(),
                        CLAIM_ROLE, user.getRole().name()));
    }

    public String generateToken(Integer userId, Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * 驗簽並解析 token。
     *
     * @throws JwtException 簽章不符、格式錯誤或已過期
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 僅檢查 token 是否有效，不拋例外 */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** 從 token 取出 user id；token 無效時拋 JwtException */
    public Integer extractUserId(String token) {
        String subject = parseClaims(token).getSubject();
        try {
            return Integer.valueOf(subject);
        } catch (NumberFormatException ex) {
            throw new JwtException("token subject 不是合法的 user id: " + subject, ex);
        }
    }

    public String extractEmail(String token) {
        return parseClaims(token).get(CLAIM_EMAIL, String.class);
    }

    public String extractRole(String token) {
        return parseClaims(token).get(CLAIM_ROLE, String.class);
    }
}
