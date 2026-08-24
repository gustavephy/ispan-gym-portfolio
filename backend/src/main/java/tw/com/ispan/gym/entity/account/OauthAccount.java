package tw.com.ispan.gym.entity.account;


import tw.com.ispan.gym.enums.account.OAuthProvider;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * OAuth 登入綁定。OAuth 是登入方式，屬帳號域，故綁 {@link Account} 而非 member——
 * 員工帳號同樣能走 OAuth 登入（2026-08-13 跟進 feat/member-register-verify 的設計）。
 */
@Entity
@Table(name = "oauth_account", uniqueConstraints = @UniqueConstraint(name = "UX_oauth_account_provider", columnNames = {
		"oauth_provider", "provider_user_id" }))
@Getter
@Setter
public class OauthAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "oauth_account_id")
	private Integer id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_oauth_account_account_id"))
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(name = "oauth_provider", length = 20, nullable = false)
	private OAuthProvider oauthProvider;

	@Column(name = "provider_user_id", length = 255, nullable = false)
	private String providerUserId;

	/**
	 * 以下三欄是 Google 端最近一次提供的顯示資訊，跟 {@link #providerUserId} 這個
	 * 真正的身分主鍵不同——它們可以隨時間改變，不影響登入判定，只在成功登入時更新。
	 * 既有綁定沒有這些資料，故一律可空，不回填假值，於下次成功登入時自然補齊
	 * （見 SchemaFixupRunner）。
	 */
	@Column(name = "provider_email", length = 100)
	private String providerEmail;

	@Column(name = "provider_email_verified")
	private Boolean providerEmailVerified;

	@Column(name = "last_login_at")
	private LocalDateTime lastLoginAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
