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

    @Bean
    InitializingBean orderPaymentIdSchemaFixup(DataSource dataSource) {
        return () -> migrateOrderPaymentIdsToInt(new JdbcTemplate(dataSource));
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor orderPaymentIdSchemaFixupDependsOn() {
        return new EntityManagerFactoryDependsOnPostProcessor("orderPaymentIdSchemaFixup") {
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

    // === 訂單／付款／報表子表主鍵改型別（2026-08-19 定案：orders、order_item、
    // order_status_history、payment_transaction、payment_refund、payment_webhook，
    // 以及 report_snapshot 的三張 cascade 子表 report_daily_total／report_breakdown／
    // report_sales_item，id 由 bigint 改 int，跟其餘模組 member/employee/trainer/
    // cart_item/report_snapshot 的慣例一致）=========================================
    // 跟 cart_item 同樣的限制：SQL Server 不允許直接 ALTER 一個 IDENTITY 欄位的型別，
    // 只能整張表重建。這批表不是暫存資料，但目前僅本機測試資料，直接砍表重建、
    // 不遷移舊資料（已與使用者確認接受清空）。
    //
    // 這個叢集外面有 3 個表帶外鍵指進來，這些表本身不砍，只清掉會變孤兒的那幾列：
    //   - member_subscription.order_id 是 NOT NULL，訂單沒了那幾筆本來就不可能存在，整列刪除
    //   - member_course_quota.order_item_id 可為 NULL，只清空這個欄位
    //   - inventory_log.ref_order_id 可為 NULL，同樣只清空欄位（目前沒有資料，防禦性處理）
    private void migrateOrderPaymentIdsToInt(JdbcTemplate jdbc) {
        if (tableExists(jdbc, "order")) {
            dropForeignKeysReferencing(jdbc, "order");
            jdbc.execute("DROP TABLE [order]");
            log.info("已移除舊的 order 表及其相關外鍵（解決 FK_orders_subscription_plan 重複衝突）");
        }

        if (tableExists(jdbc, "member_course_quota") && columnExists(jdbc, "member_course_quota", "id")) {
            dropForeignKeysReferencing(jdbc, "member_course_quota");
            jdbc.execute("DROP TABLE member_course_quota");
            log.info("已移除舊 id 的 member_course_quota 表，由 ddl-auto 重建");
        }

        // password_reset_token 的主鍵同樣從 id 改名為 password_reset_token_id。ddl-auto=update
        // 不會改名欄位，只會在既有 IDENTITY 欄位旁再加一個，SQL Server 不允許一表兩個
        // IDENTITY，整個啟動會失敗。這張表只存有時效的重設密碼 token，砍掉不影響任何帳號。
        if (tableExists(jdbc, "password_reset_token") && columnExists(jdbc, "password_reset_token", "id")) {
            dropForeignKeysReferencing(jdbc, "password_reset_token");
            jdbc.execute("DROP TABLE password_reset_token");
            log.info("已移除舊 id 的 password_reset_token 表，由 ddl-auto 重建");
        }

        if (!tableExists(jdbc, "orders") || !columnExists(jdbc, "orders", "id")) {
            return;
        }
        // member_course_quota 可能已在上面被整張砍掉（舊 id 欄位的情況），此時後面這些
        // 針對它的清理與 ALTER 都要跳過——對不存在的表下語句會讓整個 context 起不來。
        boolean quotaTableExists = tableExists(jdbc, "member_course_quota");

        jdbc.execute("DELETE FROM member_subscription WHERE order_id IS NOT NULL");
        if (quotaTableExists) {
            jdbc.execute("UPDATE member_course_quota SET order_item_id = NULL WHERE order_item_id IS NOT NULL");
        }
        jdbc.execute("UPDATE inventory_log SET ref_order_id = NULL WHERE ref_order_id IS NOT NULL");

        // 光清空孤兒資料不會改變欄位型別，這三個欄位本身也要從 bigint 改 int，
        // 不然 Hibernate 等一下想在新表上補外鍵時會因為型別對不上而失敗（orders.id 已經是 int）。
        // 不是 IDENTITY 欄位，可以直接 ALTER COLUMN，不用整欄重建。
        // SQL Server 不允許 ALTER 一個被外鍵參照的欄位（error 5074），得先把「掛在這個欄位上」
        // 的外鍵拆掉。注意跟下面迴圈用的 dropForeignKeysReferencing 方向相反：那支拆的是
        // 「別人指向這張表」的外鍵，這裡要拆的是「這張表自己指出去」的那條。
        // 拆掉後不必自己補回來——這三個欄位指向的 orders／order_item 等表待會就整張重建，
        // ddl-auto 會依 entity 的 @JoinColumn 重新建立外鍵。
        dropForeignKeysOnColumn(jdbc, "member_subscription", "order_id");
        if (quotaTableExists) {
            dropForeignKeysOnColumn(jdbc, "member_course_quota", "order_item_id");
        }
        dropForeignKeysOnColumn(jdbc, "inventory_log", "ref_order_id");

        jdbc.execute("ALTER TABLE member_subscription ALTER COLUMN order_id int NOT NULL");
        if (quotaTableExists) {
            jdbc.execute("ALTER TABLE member_course_quota ALTER COLUMN order_item_id int NULL");
        }
        jdbc.execute("ALTER TABLE inventory_log ALTER COLUMN ref_order_id int NULL");

        for (String table : new String[] {
                "order_status_history", "payment_refund", "payment_transaction",
                "order_item", "orders", "payment_webhook",
                "report_daily_total", "report_breakdown", "report_sales_item" }) {
            if (!tableExists(jdbc, table)) {
                continue;
            }
            dropForeignKeysReferencing(jdbc, table);
            jdbc.execute("DROP TABLE " + table);
        }
        log.info("已移除訂單／付款／報表子表的舊 id 表，新表由 ddl-auto 重建");
    }

    private boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID(?) AND name = ?",
                Integer.class, table, column);
        return found != null && found > 0;
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

    /**
     * 拆掉「這張表的這個欄位」上掛的外鍵——方向與 {@link #dropForeignKeysReferencing}
     * 相反（那支拆的是別人指向該表的）。ALTER COLUMN 改型別前必須先拆，否則 SQL Server
     * 會回 error 5074「物件與資料行相依」。
     */
    private void dropForeignKeysOnColumn(JdbcTemplate jdbc, String table, String column) {
        if (!tableExists(jdbc, table)) {
            return;
        }
        var fks = jdbc.queryForList("""
                SELECT fk.name
                    FROM sys.foreign_keys fk
                    JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                    JOIN sys.columns c ON c.object_id = fkc.parent_object_id
                                      AND c.column_id = fkc.parent_column_id
                    WHERE fk.parent_object_id = OBJECT_ID(?) AND c.name = ?
                """, table, column);
        for (var row : fks) {
            jdbc.execute("ALTER TABLE [" + table + "] DROP CONSTRAINT [" + row.get("name") + "]");
            log.info("已移除 {}.{} 上的外鍵 {}（ALTER COLUMN 前必須先拆）", table, column, row.get("name"));
        }
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
