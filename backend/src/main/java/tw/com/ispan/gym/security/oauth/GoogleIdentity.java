package tw.com.ispan.gym.security.oauth;

/**
 * 從 Google OIDC claims 抽出的最小必要資訊。只有 {@code subject} 是身分主鍵，
 * 其餘欄位（尤其 email）只在建立綁定或補資料時使用，已綁定帳號的後續登入
 * 一律信任 subject，不因後續某次 claims 缺 email 就重新判定身分。
 */
public record GoogleIdentity(
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName,
        String picture) {}
