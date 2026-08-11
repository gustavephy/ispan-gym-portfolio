package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * member_id 僅存整數，不建立跨模組 JPA 關聯（member 表由其他組員維護）。
 * 「同會員同天不得重複上傳」由 UX_inbody_member_date 篩選式唯一索引維護於 DDL，
 * JPA 的 @Table(indexes=...) 無法表達 WHERE deleted_at IS NULL 這種條件，
 * 因此該唯一索引不在此處宣告，僅在資料庫 Schema SQL 中建立。
 * 軟刪除後可重新上傳，因此索引條件為 WHERE deleted_at IS NULL。
 *
 * ★ @NotNull／@DecimalMin／@DecimalMax 需要 spring-boot-starter-validation
 *   依賴存在才會生效，未加此依賴時這些註解會被安靜忽略。
 */
@Entity
@Table(
        name = "inbody_records",
        indexes = @Index(name = "IX_inbody_member_id", columnList = "member_id")
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

    @NotNull
    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @NotNull
    @PastOrPresent
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
    @DecimalMax("150.00")
    @Column(name = "muscle_mass", precision = 5, scale = 2)
    private BigDecimal muscleMass;

    @DecimalMin("10.00")
    @DecimalMax("80.00")
    @Column(precision = 5, scale = 2)
    private BigDecimal bmi;

    /** 軟刪除；不可改用 JPA 的 delete，以保留 AI 課表的量測依據。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        validateMeasurement();
    }

    @PreUpdate
    private void onUpdate() {
        validateMeasurement();
    }

    private void validateMeasurement() {
        if (height == null && weight == null && bodyFat == null
                && muscleMass == null && bmi == null) {
            throw new IllegalStateException("至少要填寫一項 InBody 量測值");
        }
    }
}
