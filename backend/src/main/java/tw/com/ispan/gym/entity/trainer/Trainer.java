package tw.com.ispan.gym.entity.trainer;

import tw.com.ispan.gym.entity.employee.Employee;

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
import tw.com.ispan.gym.enums.trainer.TrainerStatus;

/**
 * 教練明細：ACTIVE 員工經 ADMIN 建立後一人一筆，存專業資料。
 * 是否為教練純看本表資料列是否存在，與 {@link Employee#getRole()} 無關。
 * 供給鏈（課程、時段、核銷）FK 皆指本表，由 DB 保證開課者必為教練。
 * deleted_at 獨立於 employee 軟刪除——代表「不再是教練」，
 * 員工帳號本身可能仍在職（轉任行政等）。
 */
@Entity
@Table(name = "trainer", uniqueConstraints = @UniqueConstraint(name = "UX_trainer_employee_id", columnNames = "employee_id"))
@Getter
@Setter
public class Trainer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "trainer_id")
	private Integer id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false, foreignKey = @ForeignKey(name = "FK_trainer_employee"))
	private Employee employee;

	@Nationalized
	@Column(name = "first_name", length = 255, nullable = false)
	private String firstName;

	@Nationalized
	@Column(name = "last_name", length = 255, nullable = false)
	private String lastName;

	@Nationalized
	@Column(name = "email", length = 255, nullable = false)
	private String email;

	/** DB 欄位沿用既有的 avatar_url，Java／DTO 對外統一叫 profileImageUrl（規格用語） */
	@Nationalized
	@Column(name = "avatar_url", length = 255)
	private String profileImageUrl;

	@Nationalized
	@Column(name = "specialty", length = 255, nullable = false)
	private String specialty;

	@Nationalized
	@Column(name = "experience", length = 1000, nullable = false)
	private String experience;

	@Nationalized
	@Column(name = "bio", nullable = false, columnDefinition = "nvarchar(max)")
	private String bio;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private TrainerStatus status = TrainerStatus.ACTIVE;

	/** 軟刪除＝不再是教練（獨立於 employee 的軟刪除） */
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

	/** 顯示用全名，一律「姓 + 名」（中文慣例），以 Trainer 自帶的姓名欄位組成 */
	@jakarta.persistence.Transient
	public String getDisplayName() {
		return (lastName == null ? "" : lastName) + (firstName == null ? "" : firstName);
	}
}
