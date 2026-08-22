package tw.com.ispan.gym.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 純 Mockito 單元測試：{@link GoogleIdentityMapper} 只是把已驗證過的 {@link OidcUser}
 * claims 攤平成 {@link GoogleIdentity}，重點確認 email_verified 缺值時不誤判成 true，
 * 以及不會自己去拆 name/嘗試從 name 猜 given/family。
 */
class GoogleIdentityMapperTest {

    private final GoogleIdentityMapper mapper = new GoogleIdentityMapper();

    @Test
    void map_正常claims全部帶入() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getSubject()).thenReturn("google-sub-123");
        when(oidcUser.getEmail()).thenReturn("user@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getGivenName()).thenReturn("小明");
        when(oidcUser.getFamilyName()).thenReturn("王");
        when(oidcUser.getPicture()).thenReturn("https://example.com/pic.jpg");

        GoogleIdentity identity = mapper.map(oidcUser);

        assertThat(identity.subject()).isEqualTo("google-sub-123");
        assertThat(identity.email()).isEqualTo("user@example.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.givenName()).isEqualTo("小明");
        assertThat(identity.familyName()).isEqualTo("王");
        assertThat(identity.picture()).isEqualTo("https://example.com/pic.jpg");
    }

    /** email_verified 缺值（null）時必須視為未驗證，不能預設為 true */
    @Test
    void map_emailVerified為null時視為未驗證() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getSubject()).thenReturn("google-sub-123");
        when(oidcUser.getEmail()).thenReturn("user@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(null);

        GoogleIdentity identity = mapper.map(oidcUser);

        assertThat(identity.emailVerified()).isFalse();
    }

    @Test
    void map_emailVerified明確為false() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getSubject()).thenReturn("google-sub-123");
        when(oidcUser.getEmailVerified()).thenReturn(false);

        GoogleIdentity identity = mapper.map(oidcUser);

        assertThat(identity.emailVerified()).isFalse();
    }

    /** 只有 name（沒有 given_name/family_name）時，mapper 不負責拆分——這裡只是確認
     *  兩個欄位各自忠實反映 claims 內容，不會意外把 name 塞進 givenName/familyName */
    @Test
    void map_只有Name沒有GivenFamilyName時兩者皆為null_不自行拆分() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getSubject()).thenReturn("google-sub-123");
        when(oidcUser.getEmail()).thenReturn("user@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getGivenName()).thenReturn(null);
        when(oidcUser.getFamilyName()).thenReturn(null);

        GoogleIdentity identity = mapper.map(oidcUser);

        assertThat(identity.givenName()).isNull();
        assertThat(identity.familyName()).isNull();
    }

    @Test
    void map_Email缺值時subject仍正確帶入() {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getSubject()).thenReturn("google-sub-123");
        when(oidcUser.getEmail()).thenReturn(null);
        when(oidcUser.getEmailVerified()).thenReturn(null);

        GoogleIdentity identity = mapper.map(oidcUser);

        assertThat(identity.subject()).isEqualTo("google-sub-123");
        assertThat(identity.email()).isNull();
        assertThat(identity.emailVerified()).isFalse();
    }
}
