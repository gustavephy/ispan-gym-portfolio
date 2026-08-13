package tw.com.ispan.gym.entity.AiHealthChat;

import tw.com.ispan.gym.entity.employee.Employee;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {

    /**
     * 對話對象只分員工／AI，一律指向 employee_id（不使用 trainer_id）。
     * trainer 表只是教練專業檔案的延伸資料，不是登入或訊息收發的身份基礎。
     */
    public enum ChatCounterpartType {
        EMPLOYEE, AI
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "counterpart_type", nullable = false, length = 20)
    private ChatCounterpartType counterpartType;

    /** 僅 counterpartType=EMPLOYEE 時有值；AI 時必須為 null。 */
    @Column(name = "employee_id")
    private Integer employeeId;

    /** true=這則是學員傳的；false=對方（員工/AI）傳的。不限制誰先開口。 */
    @Builder.Default
    @Column(name = "sender_is_member", nullable = false)
    private boolean senderIsMember = true;

    /** 由 syncRoomIdAndValidate() 自動組出，不開放外部直接指定。 */
    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    /** 中文內容必須 NVARCHAR，缺 @Nationalized 會建成 varchar，中文全變問號。 */
    @NotBlank
    @Nationalized
    @Column(name = "message_content", nullable = false, length = 500)
    private String messageContent;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 由 Spring Auditing（台北時區）於寫入時填入；@PrePersist 的 null 檢查為後備。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * ★ Service 層權限守則（Entity 不負責身份驗證，僅列於此提醒）：
     *   - 學員只能操作自己 memberId 底下的對話
     *   - 員工只能操作自己 employeeId 底下的對話
     *   - 只有系統（AiAdvisorService）可寫入 AI 回覆
     *   - 不可標記非自己收到的訊息為已讀
     */
    public void markAsRead() {
        this.read = true;
        this.readAt = LocalDateTime.now();
    }

    /**
     * 這裡刻意保留 IllegalStateException 而非改用 BusinessRuleException：
     * @PrePersist 拋出的例外會被 Hibernate 包成 PersistenceException 再往上傳，
     * GlobalExceptionHandler 收到的並非原始型別，換型別也不會變成 409。
     * 且下列三項本質是輸入驗證（應為 400），正確做法是在接上 controller 時
     * 移到 DTO 的 @NotNull／service 層驗證，而非留在 entity callback。
     */
    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        syncRoomIdAndValidate();
    }

    @PreUpdate
    private void onUpdate() {
        syncRoomIdAndValidate();
    }

    private void syncRoomIdAndValidate() {
        if (memberId == null) {
            throw new IllegalStateException("member_id 不可為空");
        }
        if (messageContent == null || messageContent.trim().isEmpty()) {
            throw new IllegalStateException("訊息內容不可為空白");
        }
        if (counterpartType == null) {
            throw new IllegalStateException("counterpart_type 不可為空");
        }

        if (counterpartType == ChatCounterpartType.EMPLOYEE) {
            if (employeeId == null) {
                throw new IllegalStateException("counterpart_type=EMPLOYEE 時 employee_id 不可為空");
            }
            roomId = "member_" + memberId + "_employee_" + employeeId;
        } else {
            if (employeeId != null) {
                throw new IllegalStateException("counterpart_type=AI 時 employee_id 必須為空");
            }
            roomId = "member_" + memberId + "_ai";
        }

        if (!read) {
            readAt = null;
        } else if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }
}
