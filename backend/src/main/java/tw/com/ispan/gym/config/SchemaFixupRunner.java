package tw.com.ispan.gym.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate ddl-auto 做不到的 schema 設定。
 *
 * <p>執行時機：context 啟動完成之後（原本是 data.sql，由
 * defer-datasource-initialization 保證排在 Hibernate 建表之後）。
 * 目前沒有任何 &#64;PostConstruct 或 &#64;Scheduled 會在這之前碰資料庫，
 * 所以延後到這裡執行是安全的。
 *
 * <p>規則：這裡所有敘述都必須是冪等的（重複執行安全），因為每次啟動都會跑，
 * 開了 devtools 的話每次熱重啟也會跑。一次性的資料遷移不要放這裡。
 *
 * <p>&#64;Order(0) 是為了讓之後的 seeder 用更大的數字排在後面 ——
 * 資料要等 schema 補完才進得去。
 */
@Component
@Order(0)
public class SchemaFixupRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixupRunner.class);

    private final JdbcTemplate jdbc;

    SchemaFixupRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        narrowColumnLengths();
        createFilteredUniqueIndexes();
        backfillExistingRows();
        migrateOauthBindingsToAccountTable();
        dropObsoleteColumns();
        dropDuplicateUniqueIndexes();
    }

    // === 欄位長度收斂 =========================================================
    // 狀態類欄位值域封閉,長度收斂為 10;gender 有 OTHER 兜底,收為 5;
    // phone 依台灣門號格式收為 10。分類類欄位(role、oauth_provider、
    // products.type)值域會隨業務擴充,保留 20 不動。
    // ddl-auto 不會縮既有欄位,必須自己 ALTER;縮之前檢查既有資料長度,
    // 超過目標寬度就略過並留警告,絕不截斷資料。
    // coach_slots.status / group_class_bookings.status 被篩選唯一索引的
    // WHERE 條件引用,需要縮時先卸索引,由後續 createFilteredUniqueIndexes
    // 重建 —— 因此本方法必須排在建索引之前執行。

    private void narrowColumnLengths() {
        if (varcharWiderThan("coach_slots", "status", 10)) {
            dropIndexIfExists("coach_slots", "UX_coach_slots_trainer_start");
        }
        if (varcharWiderThan("group_class_bookings", "status", 10)) {
            dropIndexIfExists("group_class_bookings", "UX_gcb_session_member");
        }

        narrowVarchar("users", "gender", 5);
        narrowVarchar("users", "phone", 10);
        narrowVarchar("users", "status", 10);
        narrowVarchar("coach_slots", "status", 10);
        narrowVarchar("coach_bookings", "status", 10);
        narrowVarchar("group_class_sessions", "status", 10);
        narrowVarchar("group_class_bookings", "status", 10);
        narrowVarchar("orders", "status", 10);
        narrowVarchar("user_coupons", "status", 10);
        narrowVarchar("subscription", "status", 10);
    }

    // === 篩選唯一索引 =========================================================
    // 這三個都帶 WHERE 條件，UNIQUE CONSTRAINT 語法上做不到，
    // 因此無法用 JPA 的 @UniqueConstraint 表達。

    private void createFilteredUniqueIndexes() {
        // 同一場團課、同一位會員只能有一筆有效報名。
        // 限定 status = 'BOOKED' 才能讓「取消後重新報名」成立。
        createFilteredUniqueIndex("UX_gcb_session_member", """
                CREATE UNIQUE INDEX UX_gcb_session_member
                    ON group_class_bookings(session_id, member_id)
                    WHERE status = 'BOOKED'
                """);

        // 同一位教練、同一個開始時間只能有一個有效時段。
        // 已取消的時段應允許在同一時間重新開放，故排除 CANCELLED。
        // 註：只擋開始時間完全相同者，部分重疊仍須由 service 層檢查。
        createFilteredUniqueIndex("UX_coach_slots_trainer_start", """
                CREATE UNIQUE INDEX UX_coach_slots_trainer_start
                    ON coach_slots(trainer_id, start_at)
                    WHERE status <> 'CANCELLED'
                """);

        // 一張已領取的優惠券只能用在一筆訂單。
        // 需要 WHERE 是因為大多數訂單沒用券，而 SQL Server 的唯一索引
        // 只允許「一列」為 NULL，不加條件會在第二筆無券訂單就爆掉。
        createFilteredUniqueIndex("UX_orders_user_coupon", """
                CREATE UNIQUE INDEX UX_orders_user_coupon
                    ON orders(user_coupon_id)
                    WHERE user_coupon_id IS NOT NULL
                """);

        // 同一使用者同一天只能有一筆有效的 InBody 記錄。
        // 本表採軟刪除，限定 deleted_at IS NULL 才能讓「刪除後重新上傳」成立。
        // 早期版本以一般 UNIQUE CONSTRAINT 表達（擋不住軟刪除重傳），先清掉。
        dropConstraintIfExists("inbody_records", "UQ_inbody_user_date");
        createFilteredUniqueIndex("UX_inbody_user_date", """
                CREATE UNIQUE INDEX UX_inbody_user_date
                    ON inbody_records(user_id, update_date)
                    WHERE deleted_at IS NULL
                """);
    }

    // === 既有資料回填 =========================================================
    // ddl-auto=update 新增欄位時一律建成 nullable，既有列會是 NULL。
    // @Version 欄位若為 NULL，Hibernate 更新該實體時會出錯，必須補 0。
    // 資料庫是空的時候這些 UPDATE 影響 0 列，不會有副作用。

    private void backfillExistingRows() {
        backfill("user_course_quota.version",
                "UPDATE user_course_quota SET version = 0 WHERE version IS NULL");

        backfill("group_class_sessions.version",
                "UPDATE group_class_sessions SET version = 0 WHERE version IS NULL");

        backfill("group_class_sessions.booked_count",
                "UPDATE group_class_sessions SET booked_count = 0 WHERE booked_count IS NULL");

        // 場次容量沿用所屬課程的預設值
        backfill("group_class_sessions.capacity", """
                UPDATE s SET s.capacity = c.max_capacity
                    FROM group_class_sessions s
                    JOIN courses c ON c.id = s.course_id
                    WHERE s.capacity IS NULL
                """);
    }

    // === OAuth 綁定回歸 user_oauth_accounts ===================================
    // users.oauth_provider / oauth_sub 是整併重複 User entity 時混入的第二套
    // 設計，缺唯一約束。綁定資料遷回原設計的 user_oauth_accounts
    //（有 UX_user_oauth_accounts_provider 複合唯一約束）後，
    // 移除 users 上的兩個欄位。
    // 註：若歷史資料中兩個 user 共用同一組 (provider, sub)，只有第一筆會遷入，
    // 其餘略過 —— 那本來就是缺唯一約束造成的髒資料，遷移量請看啟動 log。

    private void migrateOauthBindingsToAccountTable() {
        if (columnExists("users", "oauth_sub")) {
            backfill("user_oauth_accounts", """
                    INSERT INTO user_oauth_accounts (user_id, oauth_provider, provider_user_id, created_at)
                    SELECT u.id, u.oauth_provider, u.oauth_sub, SYSDATETIME()
                        FROM users u
                        WHERE u.oauth_sub IS NOT NULL AND u.oauth_provider IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM user_oauth_accounts a
                                              WHERE a.oauth_provider = u.oauth_provider
                                                AND a.provider_user_id = u.oauth_sub)
                    """);
        }
        dropColumnIfExists("users", "oauth_provider");
        dropColumnIfExists("users", "oauth_sub");
    }

    // === 清除已淘汰的欄位 =====================================================
    // orders.coupon_id 已由 orders.user_coupon_id 取代（改指向 user_coupons，
    // 才能標記「這張券用掉了」並保證一張券只用一次）。
    // order_items.line_amount 原設計是 DB 計算欄位，但從未真正建立，
    // 一直是 NULL；小計已由查詢端以 price_at_buy * quantity 現算。
    // Hibernate 的 update 模式永遠不會刪欄位，只能自己來。

    private void dropObsoleteColumns() {
        dropColumnIfExists("orders", "coupon_id");
        dropColumnIfExists("order_items", "line_amount");
    }

    // === 清除重複的唯一索引 ===================================================
    // 這些欄位曾同時以具名 @UniqueConstraint 與 unique=true 宣告，
    // Hibernate 因此各建了兩個功能相同的唯一約束。unique=true 已自
    // entity 移除，這裡清掉它先前建出的那個（名稱是 Hibernate 產生的 UK_ 開頭
    // 雜湊，無法寫死，改由 sys.indexes 動態找出具名約束以外、
    // 只含該單欄的唯一索引/約束）。

    private void dropDuplicateUniqueIndexes() {
        dropDuplicateUnique("users", "email", "UX_users_email");
        dropDuplicateUnique("email_verifications", "token", "UX_email_verifications_token");
        dropDuplicateUnique("password_reset_tokens", "token", "UX_password_reset_tokens_token");
        dropDuplicateUnique("trainer_details", "user_id", "UX_trainer_details_user_id");
    }

    private void dropDuplicateUnique(String table, String column, String keepName) {
        var duplicates = jdbc.queryForList("""
                SELECT i.name AS name, i.is_unique_constraint AS is_constraint
                    FROM sys.indexes i
                    JOIN sys.index_columns ic
                        ON ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.key_ordinal = 1
                    JOIN sys.columns c
                        ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE i.object_id = OBJECT_ID(?)
                      AND i.is_unique = 1
                      AND c.name = ?
                      AND i.name <> ?
                      AND (SELECT COUNT(*) FROM sys.index_columns k
                               WHERE k.object_id = i.object_id AND k.index_id = i.index_id
                                 AND k.key_ordinal >= 1) = 1
                """, table, column, keepName);
        for (var row : duplicates) {
            String name = (String) row.get("name");
            boolean isConstraint = Boolean.TRUE.equals(row.get("is_constraint"));
            if (isConstraint) {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT [" + name + "]");
            } else {
                jdbc.execute("DROP INDEX [" + name + "] ON " + table);
            }
            log.info("已移除 {}.{} 的重複唯一索引 {}", table, column, name);
        }
    }

    // === 工具方法 =============================================================
    // 識別字（表名、欄名、索引名）都是這個類別裡的常數，不是外部輸入，
    // 所以字串拼接是安全的；SQL Server 也不接受把識別字當參數綁定。

    private void createFilteredUniqueIndex(String indexName, String ddl) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.indexes WHERE name = ?", Integer.class, indexName);
        if (found != null && found > 0) {
            log.debug("索引 {} 已存在，略過", indexName);
            return;
        }
        jdbc.execute(ddl);
        log.info("已建立篩選唯一索引 {}", indexName);
    }

    private void backfill(String target, String sql) {
        int rows = jdbc.update(sql);
        if (rows > 0) {
            log.info("已回填 {}：{} 列", target, rows);
        }
    }

    private boolean varcharWiderThan(String table, String column, int length) {
        Integer wider = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns c
                    JOIN sys.types t ON t.user_type_id = c.user_type_id
                    WHERE c.object_id = OBJECT_ID(?) AND c.name = ?
                      AND t.name = 'varchar' AND c.max_length > ?
                """, Integer.class, table, column, length);
        return wider != null && wider > 0;
    }

    private void narrowVarchar(String table, String column, int length) {
        if (!varcharWiderThan(table, column, length)) {
            return;
        }
        Integer maxLen = jdbc.queryForObject(
                "SELECT MAX(LEN(" + column + ")) FROM " + table, Integer.class);
        if (maxLen != null && maxLen > length) {
            log.warn("{}.{} 既有資料最長 {} 字元,超過目標寬度 {},略過縮欄", table, column, maxLen, length);
            return;
        }
        Boolean nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM sys.columns
                    WHERE object_id = OBJECT_ID(?) AND name = ?
                """, Boolean.class, table, column);
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column
                + " varchar(" + length + ") " + (Boolean.TRUE.equals(nullable) ? "NULL" : "NOT NULL"));
        log.info("已將 {}.{} 縮為 varchar({})", table, column, length);
    }

    private void dropIndexIfExists(String table, String indexName) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.indexes WHERE name = ? AND object_id = OBJECT_ID(?)",
                Integer.class, indexName, table);
        if (found == null || found == 0) {
            return;
        }
        jdbc.execute("DROP INDEX [" + indexName + "] ON " + table);
        log.info("已暫卸索引 {}(欄位縮寬後由後續步驟重建)", indexName);
    }

    private void dropConstraintIfExists(String table, String constraint) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.objects
                    WHERE name = ? AND parent_object_id = OBJECT_ID(?)
                """, Integer.class, constraint, table);
        if (found == null || found == 0) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
        log.info("已移除約束 {}.{}", table, constraint);
    }

    private void dropColumnIfExists(String table, String column) {
        if (!columnExists(table, column)) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        log.info("已移除淘汰欄位 {}.{}", table, column);
    }

    private boolean columnExists(String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns
                    WHERE object_id = OBJECT_ID(?) AND name = ?
                """, Integer.class, table, column);
        return found != null && found > 0;
    }
}
