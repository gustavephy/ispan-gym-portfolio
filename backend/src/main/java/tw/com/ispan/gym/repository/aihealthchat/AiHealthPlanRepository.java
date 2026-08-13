package tw.com.ispan.gym.repository.aihealthchat;

import tw.com.ispan.gym.entity.aihealthchat.AiHealthPlan;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiHealthPlanRepository extends JpaRepository<AiHealthPlan, Integer> {
    List<AiHealthPlan> findByMemberIdOrderByCreatedAtDesc(Integer memberId);

    List<AiHealthPlan> findByInbodyRecordId(Integer inbodyRecordId);
}
