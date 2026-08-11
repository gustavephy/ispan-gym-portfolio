package tw.com.ispan.gym.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * model_name 預設 'gpt-4o-mini'，@Builder.Default 只保證 Java 端 builder 的預設值，
 * 資料庫 schema 仍須另外設定 DEFAULT('gpt-4o-mini')，兩邊要同步維持一致。
 * @CreatedDate 需搭配 @EnableJpaAuditing（見 JpaAuditingConfig）才會生效。
 *
 * ★ Service 層產生課表前務必檢查：inbodyRecordId 所屬的 InbodyRecord.memberId
 *   必須等於目前登入者的 memberId，否則會有水平權限（IDOR）風險——
 *   任何人只要猜到別人的 inbodyRecordId，就可能用他人的量測資料產生課表。
 *   此檢查屬於 Service 層職責，Entity 本身不做身份驗證。
 */
@Entity
@Table(name = "ai_health_plans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AiHealthPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @NotNull
    @Column(name = "inbody_record_id", nullable = false)
    private Integer inbodyRecordId;

    /** FAT_LOSS / MUSCLE_GAIN / MAINTAIN，Service 層需驗證，資料庫 CHECK 為最後防線。 */
    @NotNull
    @Column(nullable = false, length = 20)
    private String goal;

    /** SQL Server NVARCHAR(MAX)，建議存 JSON。 */
    @NotBlank
    @Column(name = "plan_content", nullable = false, columnDefinition = "nvarchar(max)")
    private String planContent;

    @Builder.Default
    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName = "gpt-4o-mini";

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
