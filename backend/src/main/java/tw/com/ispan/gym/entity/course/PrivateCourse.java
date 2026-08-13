package tw.com.ispan.gym.entity.course;

import tw.com.ispan.gym.entity.trainer.Trainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import tw.com.ispan.gym.enums.CourseStatus;

/**
 * 私教課主檔（2026-08-13 定案：課程域拆為私教課／團課兩實體，取代單一 Course）。
 *
 * <p>私教課是「可購買」的一方：order_item 的 private_course_id 指向本表
 * （與 product_id 互斥），購買後發放 MemberCourseQuota 堂數額度，
 * 再由會員預約教練時段核銷。教練為必填——私教課必定綁定授課教練。
 */
@Entity
@Table(name = "private_course")
@Getter
@Setter
@ToString(exclude = { "trainer", "category" })
@EntityListeners(AuditingEntityListener.class)
public class PrivateCourse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "private_course_id")
	private Integer id;

	@Column(nullable = false, length = 100, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	/** 購買一單位可得的堂數 */
	@Column(name = "session_per_unit", nullable = false)
	private Integer sessionPerUnit = 1;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "trainer_id", nullable = false, foreignKey = @ForeignKey(name = "FK_private_course_trainer"))
	@JsonIgnore
	private Trainer trainer;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_category_id", foreignKey = @ForeignKey(name = "FK_private_course_course_category"))
	private CourseCategory category;

	/** 私教課以一對一為預設；小班制私教可放寬 */
	@Column(nullable = false)
	private Integer maxCapacity = 1;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private CourseStatus status = CourseStatus.ACTIVE;

	@CreatedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;
}
