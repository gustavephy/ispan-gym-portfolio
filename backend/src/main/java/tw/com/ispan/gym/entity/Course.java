package tw.com.ispan.gym.entity;


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

@Entity
@Table(name = "courses")
@Getter
@Setter
@ToString(exclude = {"trainer","category"})
@EntityListeners(AuditingEntityListener.class)
public class Course {

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "trainer_id", nullable = false)
	@JsonIgnore
	private User trainer;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "course_id")
	private Integer courseId;

	@Column(nullable = false, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(nullable = false)
	private Integer maxCapacity;
	
	@Column(nullable=false, columnDefinition="int default 1")
	private Integer SessionPerUnit=1;
	
	@Column(nullable=false, precision=10, scale=2)
	private BigDecimal price;
	
	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;
	
	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isOpenForSubscription = false;
	
	//團課or私教課
	@Column(nullable = false, columnDefinition = "VARCHAR(20)")
	private CourseType courseType;

	@Column(nullable = false, columnDefinition = "VARCHAR(20) default 'ACTIVE'")
	@Enumerated(EnumType.STRING)
	private CourseStatus status = CourseStatus.ACTIVE;
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name="course_category_id")
	private CourseCategory category;
	
	@CreatedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;

}
