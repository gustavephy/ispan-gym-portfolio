package tw.com.ispan.gym.security.oauth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 登入前保存、登入成功後要導回的路徑，只接受站內相對路徑，防 open redirect。
 * 拒絕絕對 URL（{@code https://...}）、protocol-relative（{@code //evil.example}）、
 * {@code javascript:} 等 scheme，以及含控制字元的值。
 */
public final class ReturnPathValidator {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final int MAX_LENGTH = 500;

    private ReturnPathValidator() {}

    public static Optional<String> sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
            return Optional.empty();
        }
        if (CONTROL_CHARS.matcher(trimmed).find()) {
            return Optional.empty();
        }
        // 開頭已保證是單一 "/"，但 "/\evil.com" 之類仍可能被某些瀏覽器當成 protocol-relative
        if (trimmed.length() > 1 && trimmed.charAt(1) == '\\') {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
}
