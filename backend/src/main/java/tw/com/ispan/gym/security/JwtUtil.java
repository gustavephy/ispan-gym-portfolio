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
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.UserType;

/**
 * 簽發與驗證 JWT。
 * 密鑰以 Base64 設定於 app.jwt.secret，長度需至少 256 bits（HS256）。
 *
 * <p>CLAIM_ROLE 目前取自 member.role，是後台端點唯一的 ROLE_ADMIN 來源；
 * 先拔掉 Member.role 會讓 /api/admin/** 沒有任何人進得去，
 * 必須等 employee 登入能簽出帶 ROLE_ADMIN 的 token 之後才能移除。
 */
@Component
public class JwtUtil {

    /** 放 email 的自訂 claim；subject 放 id，屬於哪張表由 utype 決定 */
    public static final String CLAIM_EMAIL = "email";
    /** 放角色的自訂 claim，值為 Role enum 名稱（MEMBER / TRAINER / ADMIN） */
    public static final String CLAIM_ROLE = "role";
    /**
     * 標示 subject 屬於哪個 id 空間，值為 UserType 名稱（MEMBER / EMPLOYEE）。
     * member 與 employee 的 id 各自從 1 開始，沒有這個 claim 就無從得知
     * subject=5 指的是哪張表的第 5 號，驗證端會認錯人。
     */
    public static final String CLAIM_USER_TYPE = "utype";

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

    /** 依會員簽發 access token，subject 為 member id */
    public String generateToken(Member member) {
        return generateToken(
                member.getMemberId(),
                Map.of(
                        CLAIM_EMAIL, member.getEmail(),
                        CLAIM_ROLE, member.getRole().name(),
                        CLAIM_USER_TYPE, UserType.MEMBER.name()));
    }

    /**
     * 低階簽發入口，claims 需自行帶齊——包含 {@link #CLAIM_USER_TYPE}。
     * 未來新增 employee 登入時請走這裡並填 UserType.EMPLOYEE。
     */
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

    /**
     * 取出 subject 所屬的 id 空間。
     *
     * <p>舊 token（本 claim 加入前簽發的）沒有這個欄位，一律視為 MEMBER——
     * 當時系統只簽發得出會員 token，這個預設不會擴大任何權限。
     *
     * @throws JwtException token 無效，或 utype 值不是合法的 UserType
     */
    public UserType extractUserType(String token) {
        String raw = parseClaims(token).get(CLAIM_USER_TYPE, String.class);
        if (raw == null) {
            return UserType.MEMBER;
        }
        try {
            return UserType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new JwtException("token utype 不是合法的 UserType: " + raw, ex);
        }
    }
}
