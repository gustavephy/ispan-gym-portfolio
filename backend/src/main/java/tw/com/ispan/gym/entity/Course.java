package tw.com.ispan.gym.entity;


import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import tw.com.ispan.gym.enums.CourseStatus;

@Entity
@Table(name = "courses")
@Getter
@Setter
@ToString(exclude = {"product","trainer"})
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

	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private String courseType;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false)
	@JsonIgnore
	private Product product;

	@Column(name = "product_id", insertable = false, updatable = false)
	private Integer productId;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "trainer_id", nullable = false)
	@JsonIgnore
	private Trainer trainer;

	@Column(nullable = false)
	private Integer maxCapacity = 15;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isOpenForSubscription = false;

	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	@Enumerated(EnumType.STRING)
	private CourseStatus status = CourseStatus.ACTIVE;

	@CreatedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;

}
