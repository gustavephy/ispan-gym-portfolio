package tw.com.ispan.gym.repository.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import jakarta.persistence.EntityManager;

import tw.com.ispan.gym.config.JpaAuditingConfig;
import tw.com.ispan.gym.entity.report.ReportSnapshot;
import tw.com.ispan.gym.enums.report.ReportType;

/**
 * {@link ReportSnapshotRepository#markStaleCovering} 的 OR 條件（付款日或退款完成日任一落在區間）
 * 與三個排除條件（已過時、歷史版、已作廢）都寫在 JPQL 裡，Mockito 對 repository 的 mock 驗不到，
 * 必須連真實 SQL Server。
 *
 * <p>標 {@code @Tag("db-integration")}，預設從 {@code mvn test} 排除（見 pom.xml 的 surefire 設定）；
 * 本機要有 SQL Server 且 .env 已 source 才能跑：
 * {@code mvnw test -Dtest=ReportSnapshotRepositoryIntegrationTest -DexcludedGroups=}
 *
 * <p>{@code @DataJpaTest} 每個測試方法包在交易內、結束自動 rollback，不會在 dev DB 留下資料。
 * 區間一律用 2099 年，避免和 dev DB 既有快照撞到「現行版唯一」的篩選唯一索引。
 * {@code ReportSnapshot.generatedAt} 靠 {@code @CreatedDate} 填值，slice 測試不會載入一般
 * {@code @Configuration}，故明確 {@code @Import} 稽核設定，否則 not-null 欄位會插不進去。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Tag("db-integration")
class ReportSnapshotRepositoryIntegrationTest {

    /** 8 月付款、9 月退款：兩個日期落在不同區間，是修正前會漏標的情境 */
    private static final LocalDate PAID = LocalDate.of(2099, 8, 15);
    private static final LocalDate COMPLETED = LocalDate.of(2099, 9, 3);

    @Autowired
    private ReportSnapshotRepository snapshots;

    @Autowired
    private EntityManager em;

    private ReportSnapshot snapshot(ReportType type, LocalDate start, LocalDate end) {
        return snapshots.saveAndFlush(
                new ReportSnapshot(type, start, end, 0L, BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    /** 批次 UPDATE 不會回寫 persistence context，清掉一級快取再從 DB 讀才看得到結果 */
    private ReportSnapshot reload(ReportSnapshot s) {
        em.clear();
        return snapshots.findById(s.getId()).orElseThrow();
    }

    @Test
    void 付款日或退款完成日任一落在區間的現行版都標過時_兩者皆不涵蓋的不標() {
        ReportSnapshot coversPaid = snapshot(ReportType.MONTHLY,
                LocalDate.of(2099, 8, 1), LocalDate.of(2099, 8, 31));
        ReportSnapshot coversCompletedOnly = snapshot(ReportType.CUSTOM,
                LocalDate.of(2099, 9, 1), LocalDate.of(2099, 9, 5));
        ReportSnapshot coversNeither = snapshot(ReportType.MONTHLY,
                LocalDate.of(2099, 10, 1), LocalDate.of(2099, 10, 31));

        int affected = snapshots.markStaleCovering(PAID, COMPLETED, LocalDateTime.of(2099, 9, 3, 15, 0));

        assertThat(affected).isEqualTo(2);
        assertThat(reload(coversPaid).getStaleAt()).isNotNull();
        // 修正前只看付款日：這份「先生成、之後區間內才完成退款」的快照退款欄位已少算，卻不會被標
        assertThat(reload(coversCompletedOnly).getStaleAt()).isNotNull();
        assertThat(reload(coversNeither).getStaleAt()).isNull();
    }

    @Test
    void 歷史版與已作廢的不標_已標過的不重標() {
        ReportSnapshot superseded = snapshot(ReportType.CUSTOM,
                LocalDate.of(2099, 9, 1), LocalDate.of(2099, 9, 10));
        superseded.markSuperseded(LocalDateTime.of(2099, 9, 2, 0, 0));
        snapshots.saveAndFlush(superseded);
        ReportSnapshot voided = snapshot(ReportType.DAILY, COMPLETED, COMPLETED);
        voided.markVoided(LocalDateTime.of(2099, 9, 4, 0, 0), null, "測試作廢");
        snapshots.saveAndFlush(voided);
        ReportSnapshot current = snapshot(ReportType.CUSTOM,
                LocalDate.of(2099, 8, 10), LocalDate.of(2099, 8, 20));

        LocalDateTime first = LocalDateTime.of(2099, 9, 3, 15, 0);
        int firstRun = snapshots.markStaleCovering(PAID, COMPLETED, first);
        int secondRun = snapshots.markStaleCovering(PAID, COMPLETED, first.plusHours(1));

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isZero();
        assertThat(reload(current).getStaleAt()).isEqualTo(first); // 第二次不覆寫時間戳
        assertThat(reload(superseded).getStaleAt()).isNull();
        assertThat(reload(voided).getStaleAt()).isNull();
    }
}
