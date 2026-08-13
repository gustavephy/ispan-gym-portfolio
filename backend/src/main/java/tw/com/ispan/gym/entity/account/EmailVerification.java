package tw.com.ispan.gym.entity.account;

import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.account.VerificationPurpose;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "email_verification", uniqueConstraints = @UniqueConstraint(name = "UX_email_verification_token", columnNames = "token"))
@Getter
@Setter
public class EmailVerification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_email_verification_account_id"))
	private Account account;

	/** 這封驗證信是為了啟用哪一種身分（同一 account 可分別驗證會員／員工） */
	@Enumerated(EnumType.STRING)
	@Column(name = "target_role", length = 20, nullable = false)
	private AccountType targetRole;

	/**
	 * 這把 key 原本要開哪一道門：REGISTRATION（新註冊）或 REACTIVATION（軟刪除帳號重新啟用）。
	 * 建立當下就固定，verifyEmail() 依此決定要跑哪個分支——但門現在能不能開，仍要另外檢查
	 * 當下的 deleted_at／status，purpose 不能取代這層即時檢查。
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", length = 20, nullable = false)
	private VerificationPurpose purpose;

	@Column(name = "token", nullable = false)
	private UUID token;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "verified_at")
	private LocalDateTime verifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
