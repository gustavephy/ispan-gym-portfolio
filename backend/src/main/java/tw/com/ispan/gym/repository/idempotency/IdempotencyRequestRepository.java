package tw.com.ispan.gym.repository.idempotency;

import tw.com.ispan.gym.common.idempotency.IdempotencyRequest;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, Long> {
    Optional<IdempotencyRequest> findByMemberIdAndOperationNameAndIdempotencyKey(
            Integer memberId, String operationName, String idempotencyKey);

    /**
     * 排程清理用：過了 expiresAt 的記錄不再有防重複送出的保護作用（不論當下是 PENDING
     * 還是已經 COMPLETED），可以刪除。
     */
    @Modifying
    @Query("delete from IdempotencyRequest r where r.expiresAt < :cutoff")
    int deleteStaleRequests(@Param("cutoff") LocalDateTime cutoff);
}
