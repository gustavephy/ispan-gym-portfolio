package tw.com.ispan.gym.entity;
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

@Entity
@Table(name = "user_oauth_accounts", uniqueConstraints = @UniqueConstraint(name = "UX_user_oauth_accounts_provider", columnNames = {
		"oauth_provider", "provider_user_id" }))
@Getter
@Setter
public class UserOauthAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "FK_user_oauth_accounts_user_id"))
	private User user;

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
