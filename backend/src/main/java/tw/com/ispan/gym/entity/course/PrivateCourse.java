package tw.com.ispan.gym.entity.course;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
import tw.com.ispan.gym.entity.Trainer;
import tw.com.ispan.gym.enums.course.CourseStatus;

@Entity
@Table(name = "private_course")
@Getter
@Setter
@ToString(exclude = {"trainer","courseCategory"})
@EntityListeners(AuditingEntityListener.class)
public class PrivateCourse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "private_course_id")
	private Integer privateCourseId;
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name = "trainer_id", nullable = false)
	@JsonIgnoreProperties({"password","pwdHasd","passwordChangeAt","createdAt","updatedAt","hibernateLazyInitializer","handler"})
	private Trainer trainer;

	@Column(nullable = false, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(nullable = false)
	private Integer maxCapacity = 1;
	
	@Column(nullable=false, columnDefinition="int default 1")
	private Integer SessionPerUnit = 1;
	
	@Column(nullable=false, precision=10, scale=2)
	private BigDecimal price;
	
	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@Column(nullable = false, columnDefinition = "VARCHAR(20) default 'ACTIVE'")
	@Enumerated(EnumType.STRING)
	private CourseStatus status = CourseStatus.ACTIVE;
	
	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;

	@CreatedDate
	@Column(updatable= false, columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;

}
