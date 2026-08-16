package tw.com.ispan.gym.repository.aihealthchat;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.aihealthchat.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Integer> {

    Page<ChatMessage> findByRoomIdOrderByCreatedAtDesc(String roomId, Pageable pageable);

    /** AI 回覆組 prompt 用的最近對話上下文，呼叫端需自行反轉成時間正序 */
    List<ChatMessage> findTop10ByRoomIdOrderByCreatedAtDesc(String roomId);

    /**
     * 標記已讀走 bulk update，繞過 entity 的 @PreUpdate（不會觸發 syncRoomIdAndValidate），
     * 因此這裡要自己把 read_at 一併設好，不能只改 is_read。
     *
     * <p>bySenderIsMember 決定「誰的訊息被標記已讀」：學員讀房間時傳 false（把對方傳的訊息標已讀），
     * 員工讀房間時傳 true（把學員傳的訊息標已讀）——不可傳自己剛好相反的值，
     * 否則會變成自己標記自己傳送的訊息，見 ChatMessage 的 5.4 提醒。
     */
    @Modifying
    @Query("update ChatMessage m set m.read = true, m.readAt = CURRENT_TIMESTAMP "
            + "where m.roomId = :roomId and m.read = false and m.senderIsMember = :bySenderIsMember")
    int markRoomAsRead(@Param("roomId") String roomId, @Param("bySenderIsMember") boolean bySenderIsMember);

    /** 學員的總未讀數：對方（AI／員工）傳的、還沒讀的訊息，橫跨所有房間 */
    long countByMemberIdAndSenderIsMemberFalseAndReadFalse(Integer memberId);

    /** 員工的總未讀數：只能統計學員傳的、還沒讀的訊息，不可把自己傳的算進去 */
    long countByEmployeeIdAndSenderIsMemberTrueAndReadFalse(Integer employeeId);
}
