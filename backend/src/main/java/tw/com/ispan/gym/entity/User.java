package tw.com.ispan.gym.entity;
import tw.com.ispan.gym.enums.Gender;
import tw.com.ispan.gym.enums.Role;
import tw.com.ispan.gym.enums.UserStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "UX_users_email", columnNames = "email"))
@Getter
@Setter
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Nationalized
	@Column(name = "first_name", length = 50, nullable = false)
	private String firstName;

	@Nationalized
	@Column(name = "last_name", length = 50, nullable = false)
	private String lastName;

	@Column(name = "dob")
	private LocalDate dob;

	@Column(name = "email", length = 100, nullable = false)
	private String email;

	// 註冊表單的電話必填由 RegisterRequest 驗證把關；DB 保持可空（OAuth 帳號無電話）
	@Column(name = "phone", length = 10)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "gender", length = 5)
	private Gender gender;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", length = 20, nullable = false)
	private Role role = Role.MEMBER;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 10, nullable = false)
	private UserStatus status = UserStatus.ACTIVE;

	@Column(name = "avatar_url", length = 500)
	private String avatarUrl;

	@Nationalized
	@Column(name = "emergency_contact", length = 50)
	private String emergencyContact;

	@Column(name = "emergency_phone", length = 20)
	private String emergencyPhone;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

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
