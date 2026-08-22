package tw.com.ispan.gym.service.account.oauth;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.config.GoogleOAuthProperties;
import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteRequest;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthExchangeCode;
import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.entity.account.OauthAccount;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.account.OAuthRegistrationTicketStatus;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.OAuthRegistrationTicketInvalidException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthExchangeCodeRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 首次 Google 登入補資料完成後，同一個 transaction 內建立 Account／ACTIVE Member／
 * OauthAccount。刻意是獨立的 bean（不是 {@link GoogleOAuthRegistrationService} 的私有
 * 方法）：{@code @Transactional} 靠 Spring AOP 代理生效，同一個類別內的方法呼叫會繞過
 * 代理直接呼叫，讓這個註解形同虛設——這裡的 flush-then-catch 併發防護必須是真的在一個
 * transaction 邊界內才有意義。
 *
 * <p>每個 save() 後面接一次 flush()，讓唯一約束衝突（email／(provider,sub)）在這裡就
 * 丟出 {@link org.springframework.dao.DataIntegrityViolationException}，而不是拖到
 * transaction commit 那一刻才爆——呼叫端（{@link GoogleOAuthRegistrationService}）才能
 * 準確接住並轉交給 {@link GoogleOAuthRegistrationConflictResolver} 重新判讀。
 */
@Service
public class GoogleOAuthRegistrationCreationService {

    static final String INVALID_MESSAGE = "補資料連結無效或已過期，請重新使用 Google 登入。";

    private final OAuthRegistrationTicketRepository tickets;
    private final AccountRepository accounts;
    private final MemberRepository members;
    private final OauthAccountRepository oauthAccounts;
    private final OAuthExchangeCodeRepository exchangeCodes;
    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    public GoogleOAuthRegistrationCreationService(OAuthRegistrationTicketRepository tickets,
            AccountRepository accounts, MemberRepository members,
            OauthAccountRepository oauthAccounts, OAuthExchangeCodeRepository exchangeCodes,
            OpaqueTokenGenerator tokenGenerator, GoogleOAuthProperties properties, Clock clock) {
        this.tickets = tickets;
        this.accounts = accounts;
        this.members = members;
        this.oauthAccounts = oauthAccounts;
        this.exchangeCodes = exchangeCodes;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public String create(String tokenHash, OAuthRegistrationCompleteRequest request) {
        // ticket 的鎖定、驗證與消耗都必須在這個 transaction 內，和底下建立資料是
        // 同一筆 all-or-nothing：任何一步失敗，USED 標記會連同 Account／Member／
        // 綁定一起 rollback，ticket 保持 PENDING，使用者可以重試。
        OAuthRegistrationTicket ticket = tickets.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new OAuthRegistrationTicketInvalidException(INVALID_MESSAGE));
        LocalDateTime now = LocalDateTime.now(clock);
        if (ticket.getStatus() != OAuthRegistrationTicketStatus.PENDING || !ticket.getExpiresAt().isAfter(now)) {
            throw new OAuthRegistrationTicketInvalidException(INVALID_MESSAGE);
        }

        Account account = new Account();
        account.setEmail(ticket.getProviderEmail());
        account.setPasswordHash(null);
        account.getRoles().add(AccountType.MEMBER);
        accounts.save(account);
        accounts.flush();

        Member member = new Member();
        member.setAccount(account);
        member.setFirstName(request.getFirstName().trim());
        member.setLastName(request.getLastName().trim());
        member.setPhone(request.getPhone().trim());
        member.setDob(request.getDob());
        member.setGender(request.getGender());
        member.setStatus(UserStatus.ACTIVE);
        members.save(member);

        OauthAccount binding = new OauthAccount();
        binding.setAccount(account);
        binding.setOauthProvider(ticket.getOauthProvider());
        binding.setProviderUserId(ticket.getProviderUserId());
        binding.setProviderEmail(ticket.getProviderEmail());
        binding.setProviderEmailVerified(true);
        binding.setLastLoginAt(now);
        oauthAccounts.save(binding);
        oauthAccounts.flush();

        // 所有資料都建立成功了才消耗 ticket，和上面同屬一個 transaction
        ticket.setStatus(OAuthRegistrationTicketStatus.USED);
        ticket.setFinalizedAt(now);

        return issueExchangeCode(account, now);
    }

    private String issueExchangeCode(Account account, LocalDateTime now) {
        String rawCode = tokenGenerator.generate();
        OAuthExchangeCode code = new OAuthExchangeCode();
        code.setCodeHash(tokenGenerator.hash(rawCode));
        code.setAccount(account);
        code.setExpiresAt(now.plus(properties.exchangeCodeTtl()));
        exchangeCodes.save(code);
        return rawCode;
    }
}
