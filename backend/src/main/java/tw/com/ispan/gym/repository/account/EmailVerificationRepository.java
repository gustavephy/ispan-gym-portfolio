package tw.com.ispan.gym.repository.account;


import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tw.com.ispan.gym.entity.account.EmailVerification;
import tw.com.ispan.gym.enums.account.AccountType;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Integer> {
    Optional<EmailVerification> findByToken(UUID token);

    /** 重新註冊時清掉同帳號同身分、尚未驗證的舊驗證信，避免多封有效連結並存 */
    void deleteByAccountIdAndTargetRoleAndVerifiedAtIsNull(Integer accountId, AccountType targetRole);

    /** 重寄冷卻判斷：同帳號同身分目前這封尚未驗證的信是什麼時候發的 */
    Optional<EmailVerification> findTopByAccountIdAndTargetRoleAndVerifiedAtIsNullOrderByCreatedAtDesc(
            Integer accountId, AccountType targetRole);
}
