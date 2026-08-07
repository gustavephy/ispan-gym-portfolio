package tw.com.ispan.gym.entity;

import tw.com.ispan.gym.user.entity.User;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "courses")
@Data
@EntityListeners(AuditingEntityListener.class)
public class Course {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(nullable = false, length = 100, columnDefinition = "NVARCHAR(100)")
	private String name;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String description;

	@Column(length = 500, columnDefinition = "NVARCHAR(500)")
	private String imageUrl;

	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private String courseType;

	@ManyToOne
	@JoinColumn(name = "product_id", nullable = false)
	@JsonIgnore
	private Product product;

	@Column(name = "product_id", insertable = false, updatable = false)
	private Integer productId;

	@ManyToOne
	@JoinColumn(name = "trainer_id", nullable = false)
	@JsonIgnore
	private User trainer;

	@Column(nullable = false)
	private Integer maxCapacity = 15;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isTrial = false;

	@Column(nullable = false, columnDefinition = "bit default 0")
	private Boolean isOpenForSubscription = false;

	@Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
	private String status = "ACTIVE";

	@CreatedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(columnDefinition = "datetime2")
	private LocalDateTime updatedAt;

}
