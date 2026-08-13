package tw.com.ispan.gym.entity.course;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
 * 團體課主檔（2026-08-13 定案：課程域拆為私教課／團課兩實體，取代單一 Course）。
 *
 * <p>主檔不綁教練——教練綁在場次（GroupClassSession.trainer），以支援代課。
 * 團課不走購買流程（無價格），會員憑有效訂閱報名場次；
 * isOpenForSubscription 控制是否開放訂閱者報名。
 */
@Entity
@Table(name = "group_course")
@Getter
@Setter
@ToString(exclude = { "category" })
@EntityListeners(AuditingEntityListener.class)
public class GroupCourse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "group_course_id")
	private Integer id;

	@Column(nullable = false, length = 100, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_category_id", foreignKey = @ForeignKey(name = "FK_group_course_course_category"))
	private CourseCategory category;

	/** 課程預設容量；各場次建立時帶入，之後可依實際教室調整（GroupClassSession.capacity） */
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
