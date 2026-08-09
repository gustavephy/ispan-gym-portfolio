package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

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

    /** 由後端登入身分填入，不可直接相信前端的 userId。 */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "inbody_record_id", nullable = false)
    private Integer inbodyRecordId;

    @Column(nullable = false, length = 20)
    private String goal;

    /** SQL Server NVARCHAR(MAX)。是否限制為 JSON 由服務層契約決定。 */
    @Column(name = "plan_content", nullable = false, columnDefinition = "nvarchar(max)")
    private String planContent;

    @Builder.Default
    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName = "gpt-4o-mini";

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    /** 由 Spring Auditing（台北時區）於寫入時填入；schema 無 DB DEFAULT 可依賴。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
