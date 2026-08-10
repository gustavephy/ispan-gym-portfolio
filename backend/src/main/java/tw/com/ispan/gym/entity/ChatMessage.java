package tw.com.ispan.gym.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 由 synchronizeRoomId() 依兩端使用者 ID 產生。 */
    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    @Column(name = "sender_id", nullable = false)
    private Integer senderId;

    @Column(name = "receiver_id", nullable = false)
    private Integer receiverId;

    /** 中文內容必須 NVARCHAR；缺 @Nationalized 會建成 varchar，中文全變問號 */
    @Nationalized
    @Column(name = "message_content", nullable = false, length = 500)
    private String messageContent;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 由 Spring Auditing（台北時區）於寫入時填入；schema 無 DB DEFAULT 可依賴。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void markAsRead() {
        read = true;
        readAt = LocalDateTime.now();
    }

    /**
     * 這裡刻意保留 IllegalStateException 而非改用 BusinessRuleException：
     * @PrePersist 拋出的例外會被 Hibernate 包成 PersistenceException 再往上傳，
     * GlobalExceptionHandler 收到的並非原始型別，換型別也不會變成 409。
     * 且下列三項本質是輸入驗證（應為 400），正確做法是在接上 controller 時
     * 移到 DTO 的 @NotNull／service 層驗證，而非留在 entity callback。
     */
    @PrePersist
    @PreUpdate
    private void synchronizeRoomId() {
        if (senderId == null || receiverId == null) {
            throw new IllegalStateException("發送者與接收者不可為空");
        }
        if (senderId.equals(receiverId)) {
            throw new IllegalStateException("不可傳送訊息給自己");
        }
        if (messageContent == null || messageContent.trim().isEmpty()) {
            throw new IllegalStateException("訊息內容不可為空白");
        }

        int smallerId = Math.min(senderId, receiverId);
        int largerId = Math.max(senderId, receiverId);
        roomId = smallerId + "_" + largerId;

        if (!read) {
            readAt = null;
        } else if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }
}
