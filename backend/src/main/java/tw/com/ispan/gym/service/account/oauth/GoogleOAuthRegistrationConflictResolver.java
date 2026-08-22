package tw.com.ispan.gym.service.account.oauth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.entity.account.OAuthRegistrationTicket;
import tw.com.ispan.gym.exception.OAuthAccountLinkRequiredException;
import tw.com.ispan.gym.exception.OAuthRegistrationTicketInvalidException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.OAuthRegistrationTicketRepository;
import tw.com.ispan.gym.repository.account.OauthAccountRepository;

/**
 * {@link GoogleOAuthRegistrationCreationService#create} 因唯一約束衝突失敗後的重新判讀。
 * 刻意用 {@code REQUIRES_NEW}：呼叫端那個失敗的 transaction 已經被標記
 * rollback-only，不能在同一個 transaction 內繼續查詢（會直接丟
 * {@code UnexpectedRollbackException}），必須開一個全新的唯讀 transaction。
 */
@Service
public class GoogleOAuthRegistrationConflictResolver {

    private final OAuthRegistrationTicketRepository tickets;
    private final AccountRepository accounts;
    private final OauthAccountRepository oauthAccounts;

    public GoogleOAuthRegistrationConflictResolver(OAuthRegistrationTicketRepository tickets,
            AccountRepository accounts, OauthAccountRepository oauthAccounts) {
        this.tickets = tickets;
        this.accounts = accounts;
        this.oauthAccounts = oauthAccounts;
    }

    /**
     * 刻意收 tokenHash 而不是失敗交易裡那個 detached ticket entity：那個 entity 屬於
     * 已經 rollback 的 persistence context，在這個新交易裡重讀才拿得到可靠的值。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String resolve(String tokenHash) {
        OAuthRegistrationTicket ticket = tickets.findByTokenHash(tokenHash)
                .orElseThrow(() -> new OAuthRegistrationTicketInvalidException(
                        GoogleOAuthRegistrationCreationService.INVALID_MESSAGE));

        boolean subjectNowBound = oauthAccounts
                .findAccountByProviderAndSubject(ticket.getOauthProvider(), ticket.getProviderUserId())
                .isPresent();
        if (subjectNowBound) {
            // 併發的另一個首次登入已經完成了同一個 Google 身分的建立——不把這張 ticket
            // 直接轉成該次登入，語意保持單純：引導使用者重新走一次 Google 登入
            throw new OAuthRegistrationTicketInvalidException("此 Google 帳號已完成註冊，請重新使用 Google 登入。");
        }

        boolean emailNowExists = accounts.findByEmailIgnoreCase(ticket.getProviderEmail()).isPresent();
        if (emailNowExists) {
            throw new OAuthAccountLinkRequiredException("此 Email 已有帳號，請先使用原登入方式確認身分，再綁定 Google。");
        }

        // 兩個已知原因都排除了，代表是未知的完整性衝突——不對外揭露細節
        throw new OAuthRegistrationTicketInvalidException("註冊失敗，請重新使用 Google 登入。");
    }
}
