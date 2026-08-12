package tw.com.ispan.gym.entity;

import tw.com.ispan.gym.enums.AccountType;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 登入帳號：email／密碼的唯一來源，member 與 employee 皆以 @MapsId 共享其主鍵。
 *
 * <p>roles 支援一人多身分（員工同時也能以會員身分消費），且因為 id 空間統一，
 * JWT 的 subject 不再有跨表碰撞問題。
 */
@Entity
@Table(name = "account", uniqueConstraints = @UniqueConstraint(name = "UX_account_email", columnNames = "email"))
@Getter
@Setter
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "account_id")
	private Integer id;

	@Column(name = "email", length = 100, nullable = false)
	private String email;

	@Column(name = "password_hash", length = 255, nullable = false)
	private String passwordHash;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "account_roles",
			joinColumns = @JoinColumn(name = "account_id", foreignKey = @ForeignKey(name = "FK_account_roles_account_id")),
			uniqueConstraints = @UniqueConstraint(name = "UX_account_roles_account_id_role", columnNames = { "account_id", "role" }))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", length = 20, nullable = false)
	private Set<AccountType> roles = new HashSet<>();

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public void softDelete() {
		deletedAt = LocalDateTime.now();
	}

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
