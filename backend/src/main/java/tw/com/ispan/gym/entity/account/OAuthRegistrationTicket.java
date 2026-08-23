package tw.com.ispan.gym.entity.account;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthRegistrationTicketStatus;

/**
 * 首次 Google 登入、Email 已驗證但系統內尚無對應 Account 時核發的短效憑證，
 * 代表「Google 身分已驗證，正在等待使用者補齊 firstName／lastName／phone」——
 * Member 這三欄是 NOT NULL（見 Member.java），Google 不保證提供，因此不能在
 * callback 當下直接建立 Account/Member（2026-08-22 定案，修正原規格）。
 *
 * <p>與 {@link OAuthTransaction} 是不同的憑證：Transaction 的生命週期是「一次
 * Google 往返」，核發 ticket 當下 Transaction 就已標記 CONSUMED；Ticket 的生命週期
 * 是「等待使用者補資料」，跟 Google 是否還在線上無關。
 */
@Entity
@Table(name = "oauth_registration_ticket", uniqueConstraints = @UniqueConstraint(name = "UX_oauth_registration_ticket_token_hash", columnNames = "token_hash"))
@Getter
@Setter
public class OAuthRegistrationTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_registration_ticket_id")
    private Integer id;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20, nullable = false)
    private OAuthProvider oauthProvider;

    @Column(name = "provider_user_id", length = 255, nullable = false)
    private String providerUserId;

    @Column(name = "provider_email", length = 100, nullable = false)
    private String providerEmail;

    @Column(name = "provider_email_verified", nullable = false)
    private boolean providerEmailVerified;

    @Column(name = "suggested_first_name", length = 50)
    private String suggestedFirstName;

    @Column(name = "suggested_last_name", length = 50)
    private String suggestedLastName;

    @Column(name = "picture_url", length = 500)
    private String pictureUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private OAuthRegistrationTicketStatus status = OAuthRegistrationTicketStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
