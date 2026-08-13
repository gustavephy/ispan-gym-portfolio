package tw.com.ispan.gym.entity.member;

import tw.com.ispan.gym.entity.account.Account;

import tw.com.ispan.gym.enums.Gender;
import tw.com.ispan.gym.enums.UserStatus;

import java.time.LocalDate;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;

/**
 * 會員個人資料；登入用的 email／密碼存於 {@link Account}。
 *
 * <p>member_id 是獨立流水號，與 account_id 脫鉤（2026-08 定案，捨棄 @MapsId 共享主鍵）；
 * 以 account_id 唯一 FK 連回 {@link Account}，同一個 account 仍可同時具備會員與員工身分。
 */
@Entity
@Table(name = "member", uniqueConstraints = @UniqueConstraint(name = "UX_member_account_id", columnNames = "account_id"))
@Getter
@Setter
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_id")
	private Integer id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_member_account_id"))
	private Account account;

	@Nationalized
	@Column(name = "first_name", length = 50, nullable = false)
	private String firstName;

	@Nationalized
	@Column(name = "last_name", length = 50, nullable = false)
	private String lastName;

	@Column(name = "phone", length = 20, nullable = false)
	private String phone;

	@Column(name = "dob")
	private LocalDate dob;

	@Enumerated(EnumType.STRING)
	@Column(name = "gender", length = 10)
	private Gender gender;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 10, nullable = false)
	private UserStatus status = UserStatus.PENDING;

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
