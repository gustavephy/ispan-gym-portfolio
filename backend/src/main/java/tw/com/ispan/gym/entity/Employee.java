package tw.com.ispan.gym.entity;

import tw.com.ispan.gym.enums.EmployeeRole;
import tw.com.ispan.gym.enums.EmployeeStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;

/**
 * 員工個人資料；登入用的 email／密碼存於 {@link Account}。
 *
 * <p>employee_id 與 account_id 是同一個值（@MapsId 共享主鍵）。
 * 教練＝role=TRAINER 的員工，專業資料另存 {@link Trainer}。
 * 新帳號 status=PENDING，由管理者核准（approvedBy／approvedAt）後轉 ACTIVE。
 */
@Entity
@Table(name = "employee")
@Getter
@Setter
public class Employee {

	@Id
	@Column(name = "employee_id")
	private Integer id;

	@OneToOne(fetch = FetchType.LAZY)
	@MapsId
	@JoinColumn(name = "employee_id", foreignKey = @ForeignKey(name = "FK_employee_account_id"))
	private Account account;

	@Nationalized
	@Column(name = "first_name", length = 50, nullable = false)
	private String firstName;

	@Nationalized
	@Column(name = "last_name", length = 50, nullable = false)
	private String lastName;

	@Column(name = "phone", length = 20, nullable = false)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", length = 20, nullable = false)
	private EmployeeRole role;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 10, nullable = false)
	private EmployeeStatus status = EmployeeStatus.PENDING;

	@Column(name = "hire_date", nullable = false)
	private LocalDate hireDate;

	/** 核准此帳號的管理者；始祖帳號無人核准，維持 NULL */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approved_by", foreignKey = @ForeignKey(name = "FK_employee_approved_by"))
	private Employee approvedBy;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

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
