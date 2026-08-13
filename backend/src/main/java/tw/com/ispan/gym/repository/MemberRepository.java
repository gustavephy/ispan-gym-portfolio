package tw.com.ispan.gym.repository;

import tw.com.ispan.gym.entity.member.Member;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    /** member_id 與 account_id 已脫鉤（獨立流水號），由登入帳號反查會員資料一律走這裡 */
    Optional<Member> findByAccountId(Integer accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Member u where u.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Integer id);
}
