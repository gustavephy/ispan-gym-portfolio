package tw.com.ispan.gym.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;

/**
 * 必須趕在 Hibernate 建表之前執行的 schema 修正。
 *
 * <p>為什麼不能放在 {@link SchemaFixupRunner}：那支是 {@code CommandLineRunner}，
 * 執行時機在 context 啟動完成之後，也就是 {@code ddl-auto=update} 早就跑完了。
 * 它裡面的遷移全是「事後修補」（改名、加寬約束、backfill、補索引），事後做沒問題；
 * 但下面這種「必須先清掉舊結構、才輪得到 Hibernate 建表」的情況事後做就來不及——
 * Hibernate 會先撞上舊結構而失敗，得重啟第二次才正常。
 *
 * <p>作法：宣告成一個 bean 並讓 {@code entityManagerFactory} 依賴它
 * （{@code @DependsOn}，見下方 {@code cartItemSchemaFixup}），Spring 就會保證
 * 它在 Hibernate 初始化前完成。曾試過 {@code BeanFactoryPostProcessor}，
 * 但那個時機早到連 {@code spring.datasource.*} 都還沒繫結完成，
 * 取 DataSource 會反過來破壞正常的自動組態。
 *
 * <p>與 SchemaFixupRunner 同樣的規則：所有敘述必須冪等（每次啟動、每次 devtools
 * 熱重啟都會跑）。這裡也刻意不做任何資料遷移，只處理結構。
 */
@Configuration
public class PreSchemaFixupRunner {

    private static final Logger log = LoggerFactory.getLogger(PreSchemaFixupRunner.class);

    /**
     * 這個 bean 本身沒有對外行為，存在的意義是「被 entityManagerFactory 依賴」，
     * 藉此把下面的 schema 修正排到 Hibernate 之前。
     */
    @Bean
    InitializingBean cartItemSchemaFixup(DataSource dataSource) {
        return () -> migrateCartItemIdToInt(new JdbcTemplate(dataSource));
    }

    /**
     * 讓 entityManagerFactory 宣告依賴上面那個 bean。
     * Spring Boot 本身就是用這個機制把 Flyway/Liquibase 排在 Hibernate 之前，
     * 這裡沿用同一套，不自己發明順序控制。
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor cartItemSchemaFixupDependsOn() {
        return new EntityManagerFactoryDependsOnPostProcessor("cartItemSchemaFixup") {
        };
    }

    // === 購物車主鍵改型別（2026-08-17 定案：cart_item.id 由 bigint 改 int，
    // 欄名補實體前綴改為 cart_item_id，跟其他表的主鍵命名一致）===================
    // SQL Server 不允許直接 ALTER 一個 IDENTITY 欄位的型別，只能整張表重建；
    // 購物車是暫存資料（結帳後即清空、從未跨會員保留），直接砍表讓 ddl-auto
    // 用新型別／新欄名重建即可，不需要遷移舊資料。
    //
    // 必須在 Hibernate 之前砍掉：留著舊表的話，ddl-auto 會想在同一張表上再加一個
    // identity 欄位（cart_item_id），SQL Server 不允許一表兩個識別欄位而直接失敗。
    private void migrateCartItemIdToInt(JdbcTemplate jdbc) {
        if (!tableExists(jdbc, "cart_item") || !columnIsBigint(jdbc, "cart_item", "id")) {
            return;
        }
        dropForeignKeysReferencing(jdbc, "cart_item");
        jdbc.execute("DROP TABLE cart_item");
        log.info("已移除舊 bigint id 的 cart_item 表，int cart_item_id 新表由 ddl-auto 重建（購物車為暫存資料，不遷移）");
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
        return found != null && found > 0;
    }

    private boolean columnIsBigint(JdbcTemplate jdbc, String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.types t ON t.user_type_id = c.user_type_id
                    WHERE c.object_id = OBJECT_ID(?) AND c.name = ? AND t.name = 'bigint'
                """, Integer.class, table, column);
        return found != null && found > 0;
    }

    private void dropForeignKeysReferencing(JdbcTemplate jdbc, String table) {
        var fks = jdbc.queryForList("""
                SELECT OBJECT_NAME(parent_object_id) AS parent, name
                    FROM sys.foreign_keys
                    WHERE referenced_object_id = OBJECT_ID(?)
                """, table);
        for (var row : fks) {
            jdbc.execute("ALTER TABLE [" + row.get("parent") + "] DROP CONSTRAINT [" + row.get("name") + "]");
            log.info("已移除 {} 指向 {} 的外鍵 {}", row.get("parent"), table, row.get("name"));
        }
    }
}
