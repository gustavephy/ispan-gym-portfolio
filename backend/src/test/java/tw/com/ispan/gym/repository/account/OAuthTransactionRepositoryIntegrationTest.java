package tw.com.ispan.gym.repository.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.OAuthTransaction;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.account.OAuthProvider;
import tw.com.ispan.gym.enums.account.OAuthTransactionPurpose;

/**
 * 對應 docs/google-oauth-design-notes.md 必補案例「LOGIN transaction → projection.accountId
 * == null」「LINK transaction → projection.accountId == 正確值」——這需要真的執行
 * {@link OAuthTransactionRepository#findViewByStateHash} 的 JPQL {@code LEFT JOIN}，
 * Mockito 對 repository 的 mock 沒辦法驗證這件事，必須連真實 SQL Server。
 *
 * <p>標 {@code @Tag("db-integration")}，預設從 {@code mvn test} 排除（見 pom.xml 的
 * surefire 設定），本機要有 SQL Server 且 `.env` 已 source 才能跑：
 * {@code mvnw test -Dtest=OAuthTransactionRepositoryIntegrationTest -DexcludedGroups=}
 *
 * <p>{@code @DataJpaTest} 預設把每個測試方法包在一個 transaction 內，結束後自動
 * rollback——不用手動清理，也不會在真實 dev DB 留下測試資料，跟這個專案一貫「測試
 * 資料不留痕跡」的要求一致，比另外寫清理邏輯更可靠。{@code Replace.NONE} 是必要的：
 * 沒有它 {@code @DataJpaTest} 預設會嘗試用 embedded DB 取代真實連線，而這個專案沒有
 * H2 之類的 embedded DB 依賴，會直接啟動失敗。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("db-integration")
class OAuthTransactionRepositoryIntegrationTest {

    @Autowired
    private OAuthTransactionRepository transactions;

    @Autowired
    private tw.com.ispan.gym.repository.account.AccountRepository accounts;

    @Test
    void findViewByStateHash_Login交易AccountId為null_仍查得到() {
        OAuthTransaction transaction = new OAuthTransaction();
        transaction.setStateHash(uniqueHash("login"));
        transaction.setOauthProvider(OAuthProvider.GOOGLE);
        transaction.setPurpose(OAuthTransactionPurpose.LOGIN);
        transaction.setAccount(null);
        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        transactions.saveAndFlush(transaction);

        Optional<OAuthTransactionView> view = transactions.findViewByStateHash(transaction.getStateHash());

        // 這是這次 P2 修正真正要保證的事：account 是 null 時，明確的 LEFT JOIN 仍然要
        // 查得到這一列，不能因為隱式 join 的最佳化行為而被排除掉。
        assertThat(view).isPresent();
        assertThat(view.get().accountId()).isNull();
        assertThat(view.get().purpose()).isEqualTo(OAuthTransactionPurpose.LOGIN);
        assertThat(view.get().oauthProvider()).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    void findViewByStateHash_Link交易回傳正確AccountId() {
        Account account = new Account();
        account.setEmail("oauth-it-" + UUID.randomUUID() + "@test.local");
        account.getRoles().add(AccountType.MEMBER);
        accounts.saveAndFlush(account);

        OAuthTransaction transaction = new OAuthTransaction();
        transaction.setStateHash(uniqueHash("link"));
        transaction.setOauthProvider(OAuthProvider.GOOGLE);
        transaction.setPurpose(OAuthTransactionPurpose.LINK);
        transaction.setAccount(account);
        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        transactions.saveAndFlush(transaction);

        Optional<OAuthTransactionView> view = transactions.findViewByStateHash(transaction.getStateHash());

        assertThat(view).isPresent();
        assertThat(view.get().accountId()).isEqualTo(account.getId());
        assertThat(view.get().purpose()).isEqualTo(OAuthTransactionPurpose.LINK);
    }

    @Test
    void findViewByStateHash_不存在的StateHash回傳empty() {
        assertThat(transactions.findViewByStateHash(uniqueHash("missing"))).isEmpty();
    }

    private String uniqueHash(String label) {
        return "it-" + label + "-" + UUID.randomUUID();
    }
}
