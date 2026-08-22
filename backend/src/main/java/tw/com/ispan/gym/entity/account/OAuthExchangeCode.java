package tw.com.ispan.gym.entity.account;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Google callback 成功後核發的一次性交換碼——Callback 的 Redirect 只帶這個，
 * 絕不帶 JWT／account id／email，前端另外呼叫 {@code POST /api/auth/oauth/exchange}
 * 換取系統 JWT。只存 account id，不存角色或權限快照：兌換當下必須重新執行
 * {@link tw.com.ispan.gym.security.AccountLoginPolicy}，堵住 callback 與兌換之間
 * 帳號被停用的 TOCTOU 空隙。
 */
@Entity
@Table(name = "oauth_exchange_code",
        uniqueConstraints = @UniqueConstraint(name = "UX_oauth_exchange_code_code_hash", columnNames = "code_hash"),
        indexes = @Index(name = "IX_oauth_exchange_code_cleanup", columnList = "used_at, expires_at"))
@Getter
@Setter
public class OAuthExchangeCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_exchange_code_id")
    private Integer id;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_oauth_exchange_code_account_id"))
    private Account account;

    /** 登入成功後導回的站內相對路徑，隨兌換成功一併回給前端（JSON body，不進 URL） */
    @Column(name = "return_path", length = 500)
    private String returnPath;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
