package tw.com.ispan.gym.dto.account;

import java.time.LocalDateTime;

public record OAuthConnectionsResponse(GoogleConnection google, boolean hasPassword) {

    public record GoogleConnection(boolean linked, String email, LocalDateTime linkedAt) {}

    public static OAuthConnectionsResponse notLinked(boolean hasPassword) {
        return new OAuthConnectionsResponse(new GoogleConnection(false, null, null), hasPassword);
    }
}
