package tw.com.ispan.gym.entity.account;


import tw.com.ispan.gym.enums.OAuthProvider;

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

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
