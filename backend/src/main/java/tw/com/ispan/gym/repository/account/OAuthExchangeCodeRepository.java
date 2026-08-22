package tw.com.ispan.gym.repository.account;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.OAuthExchangeCode;

public interface OAuthExchangeCodeRepository extends JpaRepository<OAuthExchangeCode, Integer> {

    /** 唯讀查詢：只用來讀 accountId／returnPath，兌換是否成功一律看 {@link #consumeIfUnused} 的列數 */
    Optional<OAuthExchangeCode> findByCodeHash(String codeHash);

    @Modifying
    @Transactional
    @Query("update OAuthExchangeCode c set c.usedAt = :now where c.codeHash = :codeHash "
            + "and c.usedAt is null and c.expiresAt > :now")
    int consumeIfUnused(@Param("codeHash") String codeHash, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from OAuthExchangeCode c where "
            + "(c.usedAt is not null and c.usedAt < :cutoff) "
            + "or (c.usedAt is null and c.expiresAt < :cutoff)")
    int deleteStaleCodes(@Param("cutoff") LocalDateTime cutoff);
}
