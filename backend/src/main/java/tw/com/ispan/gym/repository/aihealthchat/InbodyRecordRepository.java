package tw.com.ispan.gym.repository.aihealthchat;

import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InbodyRecordRepository extends JpaRepository<InbodyRecord, Integer> {
    List<InbodyRecord> findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(Integer memberId);

    Optional<InbodyRecord> findByIdAndDeletedAtIsNull(Integer id);

    boolean existsByMemberIdAndUpdateDateAndDeletedAtIsNull(Integer memberId, LocalDate updateDate);
}
