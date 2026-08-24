package tw.com.ispan.gym.service.idempotency;

import tw.com.ispan.gym.common.idempotency.IdempotencyRequest;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.repository.idempotency.IdempotencyRequestRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用冪等保護：同一個 (memberId, operationName, idempotencyKey) 且請求內容相同時，
 * action 只會真正執行一次；重送直接回傳第一次建立的資源 id，呼叫端自行依 id 重新查詢組出回應，
 * 不快取／回放序列化過的舊回應——這樣重送拿到的永遠是資源目前的最新狀態。
 *
 * <p>idempotencyKey 由前端在 {@code Idempotency-Key} header 帶入，省略時視為未啟用保護
 * （相容沒有帶這個 header 的舊呼叫）。金鑰沿用既有的 {@code idempotency_request} 表
 * （member_id + operation_name + idempotency_key 唯一約束），此表原先建好但完全沒有程式碼引用。
 */
@Service
public class IdempotencyService {
    private static final long TTL_HOURS = 24;

    private final IdempotencyRequestRepository requests;
    private final MemberRepository members;

    public IdempotencyService(IdempotencyRequestRepository requests, MemberRepository members) {
        this.requests = requests;
        this.members = members;
    }

    /**
     * @param operationName 操作代碼；同一會員底下要能區分不同種類/目標的操作（例如取消訂單要帶上訂單 id），
     *                       避免不同操作誤用同一把金鑰互相蓋過
     * @param requestPayload 請求內容（通常直接傳 record 本身，靠其自動產生的 toString() 算 hash）；
     *                       同一把金鑰重送時內容不同視為誤用，回 409 而不是靜默套用舊結果
     * @param action 實際要執行、只能跑一次的操作，回傳新建/被操作資源的 id
     */
    @Transactional
    public Integer resolve(Integer memberId, String operationName, String idempotencyKey, Object requestPayload,
            Supplier<Integer> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }

        String hash = sha256(String.valueOf(requestPayload));
        var existing = requests.findByMemberIdAndOperationNameAndIdempotencyKey(memberId, operationName, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRequest record = existing.get();
            if (!record.getRequestHash().equals(hash)) {
                throw new BusinessRuleException("此 Idempotency-Key 已用於另一筆內容不同的請求");
            }
            if ("COMPLETED".equals(record.getStatus()) && record.getResourceId() != null) {
                return record.getResourceId().intValue();
            }
            // 同一把金鑰的請求還在處理中（極短暫的併發競態，正常路徑不會走到這裡太久）
            throw new BusinessRuleException("這個請求正在處理中，請稍後再試");
        }

        Member member = members.getReferenceById(memberId);
        IdempotencyRequest record = new IdempotencyRequest(member, operationName, idempotencyKey, hash,
                LocalDateTime.now().plusHours(TTL_HOURS));
        try {
            requests.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // 兩個併發請求同時通過上面的查詢（都還沒插入），唯一約束擋下其中一個
            throw new BusinessRuleException("這個請求正在處理中，請稍後再試");
        }

        Integer resourceId = action.get();
        record.setStatus("COMPLETED");
        record.setResourceId(resourceId == null ? null : resourceId.longValue());
        record.setCompletedAt(LocalDateTime.now());
        return resourceId;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
