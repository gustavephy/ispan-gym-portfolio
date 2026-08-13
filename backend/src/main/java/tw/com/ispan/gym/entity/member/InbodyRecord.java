package tw.com.ispan.gym.entity.member;


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
 * 「同會員同天不得重複上傳」由篩選唯一索引維護（SchemaFixupRunner）：
 *
 * CREATE UNIQUE INDEX UX_inbody_member_date
 *     ON inbody_records(member_id, update_date)
 *     WHERE deleted_at IS NULL;
 *
 * 不能用一般 @UniqueConstraint —— 本表採軟刪除，普通唯一約束
 * 會連已刪除的列一起算，使用者刪掉當天記錄後就無法重新上傳。
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

    /**
     * 同 ChatMessage：@PrePersist 的例外會被 Hibernate 包裝，改用
     * BusinessRuleException 也無法讓 GlobalExceptionHandler 正確辨識，
     * 故維持原樣，待接上 controller 時改以 DTO 驗證處理。
     */
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
