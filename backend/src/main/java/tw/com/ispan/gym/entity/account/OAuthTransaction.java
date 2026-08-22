package tw.com.ispan.gym.entity.account;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;
import tw.com.ispan.gym.enums.account.OAuthTransactionStatus;

/**
 * 一次 Google Authorization Code 往返的 server-side 紀錄。對外的 OAuth {@code state}
 * 只是一個不透明高熵值，這裡才是它真正的語意來源（purpose／account／狀態）——
 * state 本身刻意不編碼任何業務資料，見 docs/google-oauth-design-notes.md。
 *
 * <p>{@code account} 為 null 代表 LOGIN（登入前不一定知道要落地哪個帳號，
 * 也可能是全新註冊）；LINK 一律要求非 null（發起時就已登入）。兩者互斥由
 * {@code SchemaFixupRunner} 補的 CHECK 約束在 DB 層再把關一次。
 *
 * <p>{@code finalizedAt} 統一代表進入終態（CONSUMED／FAILED／SUPERSEDED）的時間，
 * PENDING 時必為 null——比拆成三個獨立時間欄位更容易寫清理查詢，也不會出現
 * 「同時有兩個終態時間有值」這種不該存在的狀態。
 */
@Entity
@Table(name = "oauth_transaction",
        uniqueConstraints = @UniqueConstraint(name = "UX_oauth_transaction_state_hash", columnNames = "state_hash"),
        indexes = {
                @Index(name = "IX_oauth_transaction_cleanup", columnList = "status, finalized_at, expires_at"),
                @Index(name = "IX_oauth_transaction_account", columnList = "account_id, oauth_provider, purpose, status")
        })
@Getter
@Setter
public class OAuthTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_transaction_id")
    private Integer id;

    @Column(name = "state_hash", length = 64, nullable = false)
    private String stateHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20, nullable = false)
    private OAuthProvider oauthProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 10, nullable = false)
    private OAuthTransactionPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OAuthTransactionStatus status = OAuthTransactionStatus.PENDING;

    /**
     * LOGIN 為 null；LINK 為發起當下已登入的 Account。不可 cascade 刪除，Account 採軟刪除。
     *
     * <p>維持 LAZY：Success/Failure Handler 不在 {@code @Transactional} 方法內，但它們
     * 讀的是 {@link tw.com.ispan.gym.repository.account.OAuthTransactionView} 投影而不是
     * 這個 entity，直接拿到 {@code accountId} 純量值，不需要為了遷就 handler 的交易邊界
     * 把這個關聯改成 EAGER（那會讓每次查 transaction 都多載入一個用不到的 Account）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", foreignKey = @ForeignKey(name = "FK_oauth_transaction_account_id"))
    private Account account;

    /** 登入成功後導回的站內相對路徑；只接受已驗證過的相對路徑，不放進 Redirect URL */
    @Column(name = "return_path", length = 500)
    private String returnPath;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
