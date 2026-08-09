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
        createFilteredUniqueIndexes();
        backfillExistingRows();
        dropObsoleteColumns();
        dropDuplicateUniqueOnUsersEmail();
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
    // users.email 曾同時以 @UniqueConstraint(UX_users_email) 與 unique=true
    // 宣告，Hibernate 因此建了兩個功能相同的唯一約束。unique=true 已自
    // entity 移除，這裡清掉它先前建出的那個（名稱是 Hibernate 產生的 UK_ 開頭
    // 雜湊，無法寫死，改由 sys.indexes 動態找出 UX_users_email 以外、
    // 只含 email 單欄的唯一索引/約束）。

    private void dropDuplicateUniqueOnUsersEmail() {
        var duplicates = jdbc.queryForList("""
                SELECT i.name AS name, i.is_unique_constraint AS is_constraint
                    FROM sys.indexes i
                    JOIN sys.index_columns ic
                        ON ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.key_ordinal = 1
                    JOIN sys.columns c
                        ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE i.object_id = OBJECT_ID('users')
                      AND i.is_unique = 1
                      AND c.name = 'email'
                      AND i.name <> 'UX_users_email'
                      AND (SELECT COUNT(*) FROM sys.index_columns k
                               WHERE k.object_id = i.object_id AND k.index_id = i.index_id
                                 AND k.key_ordinal >= 1) = 1
                """);
        for (var row : duplicates) {
            String name = (String) row.get("name");
            boolean isConstraint = Boolean.TRUE.equals(row.get("is_constraint"));
            if (isConstraint) {
                jdbc.execute("ALTER TABLE users DROP CONSTRAINT [" + name + "]");
            } else {
                jdbc.execute("DROP INDEX [" + name + "] ON users");
            }
            log.info("已移除 users.email 的重複唯一索引 {}", name);
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
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns
                    WHERE object_id = OBJECT_ID(?) AND name = ?
                """, Integer.class, table, column);
        if (found == null || found == 0) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        log.info("已移除淘汰欄位 {}.{}", table, column);
    }
}
