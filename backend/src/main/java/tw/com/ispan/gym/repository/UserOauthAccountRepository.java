package tw.com.ispan.gym.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tw.com.ispan.gym.enums.OAuthProvider;
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.entity.UserOauthAccount;

public interface UserOauthAccountRepository extends JpaRepository<UserOauthAccount, Integer> {

    @Query("""
            select a.user from UserOauthAccount a
                where a.oauthProvider = :provider and a.providerUserId = :subject
            """)
    Optional<User> findUserByProviderAndSubject(
            @Param("provider") OAuthProvider provider, @Param("subject") String subject);
}
