package tw.com.ispan.gym.repository.idempotency;

import tw.com.ispan.gym.common.idempotency.IdempotencyRequest;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, Long> {
    Optional<IdempotencyRequest> findByMemberIdAndOperationNameAndIdempotencyKey(
            Integer memberId, String operationName, String idempotencyKey);
}
