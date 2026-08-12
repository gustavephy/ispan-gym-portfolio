package tw.com.ispan.gym.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.enums.OAuthProvider;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.entity.MemberOauthAccount;

public interface MemberOauthAccountRepository extends JpaRepository<MemberOauthAccount, Integer> {

    @Query("""
            select a.member from MemberOauthAccount a
                where a.oauthProvider = :provider and a.providerUserId = :subject
            """)
    Optional<Member> findMemberByProviderAndSubject(
            @Param("provider") OAuthProvider provider, @Param("subject") String subject);
}
