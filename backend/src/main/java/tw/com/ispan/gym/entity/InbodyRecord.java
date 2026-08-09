package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "inbody_records",
        indexes = @Index(name = "IX_inbody_user_id", columnList = "user_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "UQ_inbody_user_date",
                columnNames = {"user_id", "update_date"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InbodyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /** 僅用於建立資料庫 FK；寫入時以 userId 維持既有 API 契約。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "FK_inbody_user"))
    private User user;

    @Column(name = "update_date", nullable = false)
    private LocalDate updateDate;

    @DecimalMin("50.00")
    @DecimalMax("250.00")
    @Column(precision = 5, scale = 2)
    private BigDecimal height;

    @DecimalMin("20.00")
    @DecimalMax("300.00")
    @Column(precision = 5, scale = 2)
    private BigDecimal weight;

    @DecimalMin("0.00")
    @DecimalMax("100.00")
    @Column(name = "body_fat", precision = 5, scale = 2)
    private BigDecimal bodyFat;

    @DecimalMin("0.00")
    @DecimalMax("250.00")
    @Column(name = "muscle_mass", precision = 5, scale = 2)
    private BigDecimal muscleMass;

    @DecimalMin("10.00")
    @DecimalMax("80.00")
    @Column(precision = 5, scale = 2)
    private BigDecimal bmi;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 軟刪除；不可改用 JPA 的 delete，以保留 AI 課表的量測依據。 */
    public void softDelete() {
        deletedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    private void validateMeasurement() {
        if (height == null && weight == null && bodyFat == null
                && muscleMass == null && bmi == null) {
            throw new IllegalStateException("至少要填寫一項 InBody 量測值");
        }
    }
}
