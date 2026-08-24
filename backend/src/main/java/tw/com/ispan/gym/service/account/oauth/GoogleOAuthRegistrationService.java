package tw.com.ispan.gym.service.account.oauth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tw.com.ispan.gym.dto.account.OAuthRegistrationCompleteRequest;
import tw.com.ispan.gym.security.OpaqueTokenGenerator;

/**
 * 非 transaction 的流程協調者：把補資料完成的整段工作交給
 * {@link GoogleOAuthRegistrationCreationService} 在單一 transaction 內完成
 * （ticket 鎖定／驗證／消耗＋建立 Account、Member、綁定、交換碼）；若因併發撞上
 * 唯一約束，交給 {@link GoogleOAuthRegistrationConflictResolver} 重新判讀真正原因。
 *
 * <p>這一層本身刻意不加 {@code @Transactional}：conflict resolver 必須在前一個
 * transaction 完全退出後才能開新的交易查詢，若協調者自己也在交易內就做不到。
 */
@Service
public class GoogleOAuthRegistrationService {

    private final OpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthRegistrationCreationService creationService;
    private final GoogleOAuthRegistrationConflictResolver conflictResolver;

    public GoogleOAuthRegistrationService(OpaqueTokenGenerator tokenGenerator,
            GoogleOAuthRegistrationCreationService creationService,
            GoogleOAuthRegistrationConflictResolver conflictResolver) {
        this.tokenGenerator = tokenGenerator;
        this.creationService = creationService;
        this.conflictResolver = conflictResolver;
    }

    /** @return 一次性交換碼（原始值）；前端接著呼叫 {@code POST /api/auth/oauth/exchange} 換 JWT */
    public String complete(String rawRegistrationToken, OAuthRegistrationCompleteRequest request) {
        String tokenHash = tokenGenerator.hash(rawRegistrationToken);
        try {
            // ticket 的鎖定、驗證、消耗與資料建立全都在 create() 這一個 transaction 內，
            // 這裡刻意不先行消耗——先消耗再建立的話，建立失敗會留下「ticket 已 USED、
            // 帳號卻不存在」的不可回復狀態，使用者連重試都做不到
            return creationService.create(tokenHash, request);
        } catch (DataIntegrityViolationException ex) {
            // 併發撞上唯一約束：上面那個 transaction 已 rollback（ticket 回到 PENDING），
            // 不能在已標記 rollback-only 的 transaction 內重查，交給獨立的
            // REQUIRES_NEW conflict resolver 判讀真正原因
            return conflictResolver.resolve(tokenHash);
        }
    }
}
