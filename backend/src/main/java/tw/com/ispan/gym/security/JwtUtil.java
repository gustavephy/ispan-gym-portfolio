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
import tw.com.ispan.gym.entity.Account;

/**
 * 簽發與驗證 JWT。
 * 密鑰以 Base64 設定於 app.jwt.secret，長度需至少 256 bits（HS256）。
 *
 * <p>subject 是 account_id；member 與 employee 以 @MapsId 共享 account 主鍵，
 * id 空間統一，故不需要身分別 claim 來決定查哪張表。
 *
 * <p>權限不放進 token：由 {@link JwtAuthFilter} 每次請求依 account.roles
 * 與各身分的當下狀態展開，帳號被停權時立即失效，不必等 token 過期。
 */
@Component
public class JwtUtil {

    /** 放 email 的自訂 claim；subject 放 account id */
    public static final String CLAIM_EMAIL = "email";

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

    /** 依帳號簽發 access token，subject 為 account id */
    public String generateToken(Account account) {
        return generateToken(account.getId(), Map.of(CLAIM_EMAIL, account.getEmail()));
    }

    public String generateToken(Integer accountId, Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(accountId))
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

    /** 從 token 取出 account id；token 無效時拋 JwtException */
    public Integer extractAccountId(String token) {
        String subject = parseClaims(token).getSubject();
        try {
            return Integer.valueOf(subject);
        } catch (NumberFormatException ex) {
            throw new JwtException("token subject 不是合法的 account id: " + subject, ex);
        }
    }

    public String extractEmail(String token) {
        return parseClaims(token).get(CLAIM_EMAIL, String.class);
    }
}
