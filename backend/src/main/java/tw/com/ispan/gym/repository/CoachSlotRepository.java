package tw.com.ispan.gym.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tw.com.ispan.gym.entity.CoachSlot;

public interface CoachSlotRepository extends JpaRepository<CoachSlot, Integer> {
        @Query("SELECT s FROM CoachSlot s WHERE s.trainer.id = :trainerId AND s.startAt< :endAt AND s.endAt > :startAt AND s.status <> 'CANCELLED'")
        List<CoachSlot> findOverlapping(
                        @Param("trainerId") Integer trainerId,
                        @Param("startAt") LocalDateTime startAt,
                        @Param("endAt") LocalDateTime endAt);

        List<CoachSlot> findByTrainerIdOrderByStartAtAsc(
                        Integer trainerId);
}
