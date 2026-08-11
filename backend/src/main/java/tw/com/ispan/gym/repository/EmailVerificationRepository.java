package tw.com.ispan.gym.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.EmailVerification;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Integer> {
    Optional<EmailVerification> findByToken(UUID token);
}
