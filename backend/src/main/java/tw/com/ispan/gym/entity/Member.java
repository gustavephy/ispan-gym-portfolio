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
@Table(name = "member", uniqueConstraints = @UniqueConstraint(name = "UX_member_email", columnNames = "email"))
@Getter
@Setter
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_id")
	private Integer memberId;

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

	@Column(name = "phone", length = 10, nullable = false)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "gender", length = 10)
	private Gender gender;

	// 過渡期保留：定案 member 表無 role（教練／管理職移至 employee），
	// 待 JWT 改為 utype=MEMBER/EMPLOYEE 後連同 Role enum 一併移除
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

	@Column(name = "zip_code", length = 10)
	private String zipCode;

	@Nationalized
	@Column(name = "city", length = 50)
	private String city;

	@Nationalized
	@Column(name = "district", length = 50)
	private String district;

	@Nationalized
	@Column(name = "address", length = 255)
	private String address;

	/** 軟刪除時間；刪除帳號不刪列，訂單等歷史資料仍指向本列 */
	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

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
