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
import tw.com.ispan.gym.entity.account.Account;

/**
 * 簽發與驗證 JWT。
 * 密鑰以 Base64 設定於 app.jwt.secret，長度需至少 256 bits（HS256）。
 *
 * <p>subject 是 account_id；member 與 employee 各有獨立流水號主鍵，
 * 使用端一律以 account_id 反查各身分表（findByAccountId），不可直接當成該表主鍵。
 *
 * <p>權限不放進 token：由 {@link JwtAuthFilter} 每次請求依 account.roles
 * 與各身分的當下狀態展開，帳號被停權時立即失效，不必等 token 過期。
 */
@Component
public class JwtUtil {

    /** 放 email 的自訂 claim；subject 放 account id */
    public static final String CLAIM_EMAIL = "email";

    /** 密碼變更代數（Account.credentialsVersion 的快照），用來讓改密碼前簽發的舊 token 全部失效 */
    public static final String CLAIM_CREDENTIALS_VERSION = "cv";

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
        return generateToken(account.getId(), Map.of(
                CLAIM_EMAIL, account.getEmail(),
                CLAIM_CREDENTIALS_VERSION, account.getCredentialsVersion()));
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

    /**
     * 從 token 取出 cv claim。刻意用 {@code instanceof Number} 而非
     * {@code get(CLAIM, Integer.class)}——JSON 數字反序列化的實際型別可能隨數值大小
     * 而異，強制轉型有 ClassCastException 風險。缺少 claim 一律視為 0，
     * 對應這個功能上線前簽發、身上根本沒有這個 claim 的舊 token（見 Account.credentialsVersion
     * 的新帳號一律從 0 開始），讓它們在功能上線當下不會被無關地判定失效。
     */
    public int extractCredentialsVersion(String token) {
        return extractCredentialsVersion(parseClaims(token));
    }

    /**
     * 從已驗簽的 claims 取出 cv，讓 filter/interceptor 不必重複解析同一顆 token。
     * 只有舊 token 的 claim 缺失可視為 0；型別錯誤、負數、小數及超出 int 範圍
     * 都是畸形 token，必須拒絕，不能悄悄降級成版本 0。
     */
    public int extractCredentialsVersion(Claims claims) {
        Object raw = claims.get(CLAIM_CREDENTIALS_VERSION);
        if (raw == null) {
            return 0;
        }
        if (!(raw instanceof Number number)) {
            throw new JwtException("token cv claim 不是合法的整數");
        }

        long value = number.longValue();
        if (value < 0 || value > Integer.MAX_VALUE || number.doubleValue() != (double) value) {
            throw new JwtException("token cv claim 超出合法範圍");
        }
        return (int) value;
    }
}
