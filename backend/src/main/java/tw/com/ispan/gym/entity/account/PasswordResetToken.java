package tw.com.ispan.gym.entity.account;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 忘記密碼的重設連結。對外寄出的是高熵亂數字串，這裡只存它的 SHA-256 雜湊
 * （{@link #tokenHash}）——資料庫外洩也拿不到可直接使用的憑證，查詢時把收到的
 * token 雜湊後再比對。
 *
 * <p>{@link #usedAt}／{@link #invalidatedAt} 語意不同：usedAt 是這把 token 真的
 * 完成過一次密碼重設；invalidatedAt 是被更新的申請取代、或同帳號另一把 token
 * 成功重設後一併作廢——同帳號永遠只有最新申請的那封信有效。
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
public class PasswordResetToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_password_reset_token_account_id"))
	private Account account;

	@Column(name = "token_hash", length = 255, nullable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "used_at")
	private LocalDateTime usedAt;

	@Column(name = "invalidated_at")
	private LocalDateTime invalidatedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
