package tw.com.ispan.gym.entity.course;

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
import lombok.Data;
import tw.com.ispan.gym.enums.course.CourseStatus;

@Entity
@Table(name = "group_courses")
@Data
@EntityListeners(AuditingEntityListener.class)
public class GroupCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "trainer_id", nullable = false)
    @JsonIgnoreProperties({"password", "pwdHash", "passwordChangedAt", "createdAt", "updatedAt", "hibernateLazyInitializer", "handler"})
    private Trainer trainer;

    @Column(nullable = false, length = 100, columnDefinition="NVARCHAR(100)")
    private String name;

    @Column(nullable = false)
    private Integer maxCapacity;

    @Column(columnDefinition = "NVARCHAR(500)")
    private String description;

    @Column(columnDefinition = "NVARCHAR(1000)")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20) default 'ACTIVE'")
    private CourseStatus status = CourseStatus.ACTIVE;

    @Column(nullable = false, columnDefinition = "bit default 0")
    private Boolean isTrial = false;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "course_category_id")
    private CourseCategory category;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}
