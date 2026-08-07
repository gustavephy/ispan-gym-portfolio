package tw.com.ispan.gym.entity;

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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "group_class_sessions")
@Data
@EntityListeners(AuditingEntityListener.class)
public class GroupClassSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "room_name", length = 100, columnDefinition = "NVARCHAR(100)")
    private String roomName;

    @Column(name = "start_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false, columnDefinition = "datetime2")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupClassSessionStatus status = GroupClassSessionStatus.SCHEDULED;

    @CreatedDate
    @Column(updatable = false, columnDefinition = "datetime2")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "datetime2")
    private LocalDateTime updatedAt;
}