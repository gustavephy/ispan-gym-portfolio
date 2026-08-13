package tw.com.ispan.gym.repository.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.coach.SessionDeductionLog;

public interface SessionDeductionLogRepository extends JpaRepository<SessionDeductionLog, Integer> {

}
