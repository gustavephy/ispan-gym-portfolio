package tw.com.ispan.gym.entity.employee;

import tw.com.ispan.gym.entity.account.Account;

import tw.com.ispan.gym.enums.employee.EmployeeRole;
import tw.com.ispan.gym.enums.employee.EmployeeStatus;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;

/**
 * 員工個人資料；登入用的 email／密碼存於 {@link Account}。
 *
 * <p>
 * employee_id 是獨立流水號，與 account_id 脫鉤（2026-08 定案，捨棄 @MapsId 共享主鍵）；
 * 以 account_id 唯一 FK 連回 {@link Account}。
 * 是否為教練由 {@link Trainer} 是否存在該員工的資料列決定，與 role 無關。
 * 新帳號 status=PENDING_APPROVAL，由任一 ACTIVE
 * 員工核准／拒絕（reviewedBy／reviewedAt／reviewNote）
 * 後轉 ACTIVE／REJECTED。
 */
@Entity
@Table(name = "employee", uniqueConstraints = @UniqueConstraint(name = "UX_employee_account_id", columnNames = "account_id"))
@Getter
@Setter
public class Employee {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "employee_id")
	private Integer employeeId;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, foreignKey = @ForeignKey(name = "FK_employee_account_id"))
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
	@Column(name = "status", length = 20, nullable = false)
	private EmployeeStatus status = EmployeeStatus.PENDING_APPROVAL;

	@Column(name = "hire_date", nullable = false)
	private LocalDate hireDate;

	/** 核准或拒絕此申請的員工；始祖帳號無人核准，維持 NULL */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "FK_employee_reviewed_by"))
	private Employee reviewedBy;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	/** 核准／拒絕備註；拒絕時必填，核准時選填 */
	@Nationalized
	@Column(name = "review_note", length = 500)
	private String reviewNote;

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
