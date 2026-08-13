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
import tw.com.ispan.gym.enums.CourseType;

/**
 * 課程主檔（團課與私教課）。
 *
 * <p>課程已自商品域獨立：不再需要一筆對應的 Product，價格與堂數改存本表。
 * 訂單明細以 order_item 的 course_id 指向本表（與 product_id 互斥）。
 */
@Entity
@Table(name = "courses")
@Getter
@Setter
@ToString(exclude = { "trainer", "category" })
@EntityListeners(AuditingEntityListener.class)
public class Course {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "course_id")
	private Integer courseId;

	@Column(nullable = false, length = 100, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal price;

	/** 購買一單位可得的堂數；團課憑訂閱參加，故恆為 1 */
	@Column(name = "session_per_unit", nullable = false)
	private Integer sessionPerUnit = 1;

	/** 團課或私教課 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private CourseType courseType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "trainer_id", nullable = false, foreignKey = @ForeignKey(name = "FK_courses_trainer"))
	@JsonIgnore
	private Trainer trainer;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_category_id", foreignKey = @ForeignKey(name = "FK_courses_course_category"))
	private CourseCategory category;

	@Column(nullable = false)
	private Integer maxCapacity = 15;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isOpenForSubscription = false;

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
