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
 * <p>
 * 執行時機：context 啟動完成之後（原本是 data.sql，由
 * defer-datasource-initialization 保證排在 Hibernate 建表之後）。
 * 目前沒有任何 &#64;PostConstruct 或 &#64;Scheduled 會在這之前碰資料庫，
 * 所以延後到這裡執行是安全的。
 *
 * <p>
 * 規則：這裡所有敘述都必須是冪等的（重複執行安全），因為每次啟動都會跑，
 * 開了 devtools 的話每次熱重啟也會跑。一次性的資料遷移不要放這裡。
 *
 * <p>
 * &#64;Order(0) 是為了讓之後的 seeder 用更大的數字排在後面 ——
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
        renameLegacyTables();
        renameLegacyColumns();
        clearLegacyOperatorIds();
        dropLegacyIdentityArtifacts();
        migrateLoginColumnsToAccount();
        migrateCourseDomainSplit();
        splitCourseEntity();
        narrowColumnLengths();
        createFilteredUniqueIndexes();
        createChatUnreadIndexes();
        addForeignKeys();
        addEmailVerificationPurposeColumn();
        backfillExistingRows();
        migrateOauthBindingsToAccountTable();
        rebindOauthToAccount();
        dropObsoleteColumns();
        widenOrderStatusCheck();
        dropDuplicateUniqueIndexes();
    }

    // === 舊表改名 =============================================================
    // Entity 重構定案的表改名（users → member 等，見下方清單）。
    // 本 runner 跑在 Hibernate ddl-auto 之後，屆時 Hibernate 已依新 @Table
    // 建出一張空的新表（連同指向它的新 FK），必須先把那張空表
    // 連 FK 一起拆掉，才能把帶資料的舊表改名過去。
    // 新表已有資料時代表改名早已完成（或狀態不明），一律不動。

    private void renameLegacyTables() {
        if (renameLegacyTable("users", "member")) {
            // 舊唯一約束跟著換名，避免出現「舊的被當重複清掉、新的下次啟動才建」
            // 的空窗期；dropDuplicateUniqueIndexes 之後就無事可做。
            renameUniqueIndex("member", "UX_users_email", "UX_member_email");
        }
        renameLegacyTable("subscription", "member_subscription");
        renameLegacyTable("user_course_quota", "member_course_quota");
        renameLegacyTable("user_oauth_accounts", "member_oauth_account");

        // 附屬表命名統一單數（2026-08-13 定案，跟進 feat/member-register-verify）。
        // 唯一索引與 FK 約束跟著換名，讓名稱與 entity 宣告一致，
        // Hibernate 之後才不會再建出一套同功能的重複約束。
        if (renameLegacyTable("email_verifications", "email_verification")) {
            renameUniqueIndex("email_verification", "UX_email_verifications_token", "UX_email_verification_token");
            renameConstraint("FK_email_verifications_account_id", "FK_email_verification_account_id");
        }
        if (renameLegacyTable("password_reset_tokens", "password_reset_token")) {
            renameUniqueIndex("password_reset_token", "UX_password_reset_tokens_token", "UX_password_reset_token_token");
            renameConstraint("FK_password_reset_tokens_account_id", "FK_password_reset_token_account_id");
        }
        if (renameLegacyTable("account_roles", "account_role")) {
            renameUniqueIndex("account_role", "UX_account_roles_account_id_role", "UX_account_role_account_id_role");
            renameConstraint("FK_account_roles_account_id", "FK_account_role_account_id");
        }
    }

    /** 以 sp_rename OBJECT 改名 FK 等具名約束；來源不存在就跳過 */
    private void renameConstraint(String from, String to) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.objects WHERE name = ?", Integer.class, from);
        if (found == null || found == 0) {
            return;
        }
        jdbc.execute("EXEC sp_rename '" + from + "', '" + to + "', 'OBJECT'");
        log.info("已將約束 {} 改名為 {}", from, to);
    }

    // === 舊欄位改名（user_id → member_id）====================================
    // 與表改名同理：Hibernate 已先在既有表上補了一個空的 member_id 欄位
    //（連同指向 member 的新 FK），必須先把空欄位連 FK 拆掉再 sp_rename，
    // 資料才會留在原欄位裡跟著改名。表改名之後執行，清單用的是新表名。

    private void renameLegacyColumns() {
        // 主鍵欄名加實體前綴（Java 欄位名見各 entity）
        renameColumnIfExists("member", "id", "member_id");
        renameColumnIfExists("products", "id", "product_id");
        renameColumnIfExists("product_categories", "id", "product_category_id");
        renameColumnIfExists("coupons", "id", "coupon_id");
        renameColumnIfExists("user_coupons", "id", "member_coupon_id");
        renameColumnIfExists("courses", "id", "course_id");
        renameColumnIfExists("subscription_plan", "id", "subscription_plan_id");
        renameColumnIfExists("member_subscription", "id", "member_subscription_id");
        renameColumnIfExists("inventory_log", "id", "inventory_log_id");
        // orders 指向 user_coupons 的 FK 欄跟著改名。
        // 舊篩選唯一索引的 WHERE 條件綁著 user_coupon_id，sp_rename 會被擋（5074），
        // 先卸掉，改名後由 createFilteredUniqueIndexes 以新名重建
        dropIndexIfExists("orders", "UX_orders_user_coupon");
        renameColumnIfExists("orders", "user_coupon_id", "member_coupon_id");
        // 操作者欄位改指身分域正確的表：庫存調帳→employee、銷課→trainer。
        // 舊值是 member id，在新的 id 空間裡對不上，改名後需清值（見下方 clearLegacyOperatorIds）
        renameColumnIfExists("inventory_log", "operator_id", "operator_employee_id");
        renameColumnIfExists("session_deduction_logs", "operator_id", "operator_trainer_id");
        for (String table : new String[] {
                "cart_items", "orders", "user_coupons", "member_subscription",
                "member_course_quota", "member_oauth_account", "password_history",
                "password_reset_token", "email_verification", "session_deduction_logs",
                "trainer_details", "idempotency_requests" }) {
            renameColumnIfExists(table, "user_id", "member_id");
        }
    }

    private void renameColumnIfExists(String table, String from, String to) {
        if (!columnExists(table, from)) {
            return;
        }
        if (columnExists(table, to)) {
            Integer filled = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + to + " IS NOT NULL", Integer.class);
            if (filled != null && filled > 0) {
                log.warn("{}.{} 與 {} 並存且新欄位已有資料，無法自動改名，請人工處理", table, from, to);
                return;
            }
            if (isPrimaryKeyColumn(table, to)) {
                // 空表時 ddl-auto 會直接以新欄名建表（含 PK 約束），此時舊欄不存在，
                // 走不到這裡；真走到代表新舊欄並存且新欄是 PK，非預期狀態。
                log.warn("{}.{} 是主鍵欄，不自動移除；請確認 {} 的改名狀態", table, to, table);
                return;
            }
            dropForeignKeysOnColumn(table, to);
            dropIndexesOnColumn(table, to); // 空欄位上可能已掛了 Hibernate 建的索引（5074 會擋 DROP COLUMN）
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + to);
            log.info("已移除 Hibernate 先行建立的空欄位 {}.{}", table, to);
        }
        jdbc.execute("EXEC sp_rename '" + table + "." + from + "', '" + to + "', 'COLUMN'");
        log.info("已將欄位 {}.{} 改名為 {}", table, from, to);
    }

    private boolean isPrimaryKeyColumn(String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*)
                    FROM sys.indexes i
                    JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE i.object_id = OBJECT_ID(?) AND i.is_primary_key = 1 AND c.name = ?
                """, Integer.class, table, column);
        return found != null && found > 0;
    }

    private void dropForeignKeysOnColumn(String table, String column) {
        var fks = jdbc.queryForList("""
                SELECT fk.name AS name
                    FROM sys.foreign_keys fk
                    JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                    JOIN sys.columns c ON c.object_id = fkc.parent_object_id
                                      AND c.column_id = fkc.parent_column_id
                    WHERE fk.parent_object_id = OBJECT_ID(?) AND c.name = ?
                """, table, column);
        for (var row : fks) {
            jdbc.execute("ALTER TABLE [" + table + "] DROP CONSTRAINT [" + row.get("name") + "]");
            log.info("已移除 {}.{} 上的外鍵 {}", table, column, row.get("name"));
        }
    }

    private boolean renameLegacyTable(String from, String to) {
        if (!tableExists(from)) {
            return false;
        }
        if (tableExists(to)) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + to, Integer.class);
            if (rows != null && rows > 0) {
                log.warn("{} 與 {} 同時存在且 {} 有資料，無法自動改名，請人工處理", from, to, to);
                return false;
            }
            dropForeignKeysReferencing(to);
            jdbc.execute("DROP TABLE " + to);
            log.info("已移除 Hibernate 先行建立的空 {} 表", to);
        }
        jdbc.execute("EXEC sp_rename '" + from + "', '" + to + "'");
        log.info("已將資料表 {} 改名為 {}", from, to);
        return true;
    }

    private void renameUniqueIndex(String table, String from, String to) {
        var rows = jdbc.queryForList("""
                SELECT is_unique_constraint FROM sys.indexes
                    WHERE object_id = OBJECT_ID(?) AND name = ?
                """, table, from);
        if (rows.isEmpty()) {
            return;
        }
        boolean isConstraint = Boolean.TRUE.equals(rows.get(0).get("is_unique_constraint"));
        if (isConstraint) {
            jdbc.execute("EXEC sp_rename '" + from + "', '" + to + "', 'OBJECT'");
        } else {
            jdbc.execute("EXEC sp_rename '" + table + "." + from + "', '" + to + "', 'INDEX'");
        }
        log.info("已將唯一索引 {} 改名為 {}", from, to);
    }

    private void dropForeignKeysReferencing(String table) {
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

    private boolean tableExists(String table) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name = ?", Integer.class, table);
        return found != null && found > 0;
    }

    // === 操作者 id 跨身分域失效清理 ===========================================
    // operator_id 舊值是 member id；欄位改指 employee／trainer 後，
    // 同一個數字在新的 id 空間裡是不相干的人（或不存在），留著比清掉更危險：
    // 會把調帳／銷課的責任掛到錯誤的對象身上，且 Hibernate 補 FK 時也會失敗。
    // 兩欄都可空（系統操作本來就是 null），清成 NULL 是安全的。
    // 一次性：清完之後這些欄位只會由新程式寫入正確的 id，重跑影響 0 列。

    private void clearLegacyOperatorIds() {
        clearOrphanOperatorIds("inventory_log", "operator_employee_id", "employee", "employee_id");
        clearOrphanOperatorIds("session_deduction_log", "operator_trainer_id", "trainer", "trainer_id");
    }

    private void clearOrphanOperatorIds(String table, String column, String refTable, String refColumn) {
        if (!columnExists(table, column) || !tableExists(refTable)) {
            return;
        }
        backfill(table + "." + column + "（清除跨身分域失效值）", """
                UPDATE %s SET %s = NULL
                    WHERE %s IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM %s r WHERE r.%s = %s.%s)
                """.formatted(table, column, column, refTable, refColumn, table, column));
    }

    // === 身分域改版清理（2026-08-12 定案：member / employee / trainer）========
    // trainer 改掛 employee（PK trainer_id、employee_id FK+UK），
    // 不再與 member 共享主鍵；staff 改名 employee。過渡期產物一律清除：
    // - 舊 member 制 trainer（有 member_id 欄）：直接砍，讓 ddl-auto 重建新版
    // - trainer_details：member 制教練資料無法對映到 employee 制，捨棄
    // - staff：已由 employee 取代；空表直接砍，有資料留警告

    private void dropLegacyIdentityArtifacts() {
        if (columnExists("trainer", "member_id")) {
            dropForeignKeysReferencing("trainer");
            jdbc.execute("DROP TABLE trainer");
            log.info("已移除舊 member 制 trainer 表，employee 制新表由 ddl-auto 重建");
        }
        if (tableExists("trainer_details")) {
            jdbc.execute("DROP TABLE trainer_details");
            log.warn("已移除舊表 trainer_details（member 制教練資料不再適用，未遷移）");
        }
        if (tableExists("staff")) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM staff", Integer.class);
            if (rows != null && rows > 0) {
                log.warn("staff 已由 employee 取代，但表內有 {} 列資料，請人工處理後移除", rows);
            } else {
                dropForeignKeysReferencing("staff");
                jdbc.execute("DROP TABLE staff");
                log.info("已移除空的過渡表 staff（由 employee 取代）");
            }
        }
    }

    // === 欄位長度收斂 =========================================================
    // 狀態類欄位值域封閉,長度收斂為 10;phone 依台灣門號格式收為 10。
    // role 之類值域會隨業務擴充的分類欄位保留 20 不動。
    // ddl-auto 不會縮既有欄位,必須自己 ALTER;縮之前檢查既有資料長度,
    // 超過目標寬度就略過並留警告,絕不截斷資料。
    // coach_slots.status / group_class_bookings.status 被篩選唯一索引的
    // WHERE 條件引用,需要縮時先卸索引,由後續 createFilteredUniqueIndexes
    // 重建 —— 因此本方法必須排在建索引之前執行。

    private void narrowColumnLengths() {
        if (varcharWiderThan("coach_slot", "status", 10)) {
            dropIndexIfExists("coach_slot", "UX_coach_slot_trainer_start");
        }
        if (varcharWiderThan("group_class_booking", "status", 10)) {
            dropIndexIfExists("group_class_booking", "UX_gcb_session_member");
        }

        narrowVarchar("member", "phone", 10);
        narrowVarchar("member", "status", 10);
        narrowVarchar("coach_slot", "status", 10);
        narrowVarchar("coach_booking", "status", 10);
        narrowVarchar("group_class_session", "status", 10);
        narrowVarchar("group_class_booking", "status", 10);
        narrowVarchar("orders", "status", 10);
        narrowVarchar("member_coupon", "status", 10);
        narrowVarchar("member_subscription", "status", 10);
    }

    // === 登入資料上移 account（2026-08 定案：獨立流水號 + account_id 唯一 FK）==
    // member／employee 的主鍵本來就是 IDENTITY 流水號，捨棄 @MapsId 共享主鍵後
    // 不需重建表、既有 id 與所有指向它們的 FK 資料全部保留。要做的是：
    // 1. 把舊制留在身分表上的登入欄位（email／password_hash）搬上 account
    // 2. 以 email 回填 account_id（會員與員工同 email 時共用同一個 account，正是新制語意）
    // 3. 在 account_role 補上對應身分
    // 4. 清掉身分表上的舊登入欄位；member 的 role 欄是 users 時代的產物一併移除
    //    （新制身分由 account_role 表達；舊 ADMIN 使用者請改走員工建檔流程）
    // 5. 全部回填成功後把 account_id 收成 NOT NULL + 唯一索引
    // 全新資料庫由 ddl-auto 直接建出新 schema，這裡每一步都會因欄位不存在而跳過。

    private void migrateLoginColumnsToAccount() {
        if (!tableExists("account")) {
            return; // ddl-auto 尚未建出 account（非預期），留給下次啟動再試
        }
        liftLoginColumnsToAccount("member", "MEMBER");
        liftLoginColumnsToAccount("employee", "EMPLOYEE");
        dropColumnIfExists("member", "role");
        finalizeAccountLink("member", "UX_member_account_id");
        finalizeAccountLink("employee", "UX_employee_account_id");
    }

    private void liftLoginColumnsToAccount(String table, String role) {
        if (!tableExists(table) || !columnExists(table, "email")) {
            return; // 已遷移過，或全新資料庫直接以新 schema 建表
        }
        if (!columnExists(table, "account_id")) {
            // entity 宣告 nullable=false，ddl-auto 對非空表加不了 NOT NULL 欄位
            //（SQL Server 限制），這裡先以可空型態補上，回填後由 finalizeAccountLink 收緊
            jdbc.execute("ALTER TABLE " + table + " ADD account_id int NULL");
            log.info("已為 {} 補上 account_id 欄位（暫為可空，回填後收 NOT NULL）", table);
        }
        backfill("account（自 " + table + " 搬移登入資料）", """
                INSERT INTO account (email, password_hash, created_at, updated_at)
                SELECT t.email, t.password_hash, SYSDATETIME(), SYSDATETIME()
                    FROM %s t
                    WHERE t.account_id IS NULL
                      AND NOT EXISTS (SELECT 1 FROM account a WHERE a.email = t.email)
                """.formatted(table));
        backfill(table + ".account_id", """
                UPDATE t SET account_id = a.account_id
                    FROM %s t JOIN account a ON a.email = t.email
                    WHERE t.account_id IS NULL
                """.formatted(table));
        backfill("account_role（" + role + "）", """
                INSERT INTO account_role (account_id, role)
                SELECT t.account_id, '%s' FROM %s t
                    WHERE t.account_id IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM account_role r
                                          WHERE r.account_id = t.account_id AND r.role = '%s')
                """.formatted(role, table, role));
        dropColumnIfExists(table, "email");
        dropColumnIfExists(table, "password_hash");
    }

    /** 回填完成後把 account_id 收成 NOT NULL，並補上 ddl-auto 建不出來的唯一索引 */
    private void finalizeAccountLink(String table, String uniqueIndex) {
        if (!tableExists(table) || !columnExists(table, "account_id")) {
            return;
        }
        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE account_id IS NULL", Integer.class);
        if (nulls != null && nulls > 0) {
            log.warn("{} 還有 {} 列 account_id 為 NULL，暫不收 NOT NULL 與唯一索引，請人工處理", table, nulls);
            return;
        }
        Boolean nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM sys.columns
                    WHERE object_id = OBJECT_ID(?) AND name = 'account_id'
                """, Boolean.class, table);
        if (Boolean.TRUE.equals(nullable)) {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN account_id int NOT NULL");
            log.info("已將 {}.account_id 收為 NOT NULL", table);
        }
        // 加欄那次啟動 account_id 全為 NULL，SQL Server 唯一約束建不起來
        //（Hibernate 只默默記一筆錯誤），這裡在回填後補建，名稱與 entity 宣告一致
        createFilteredUniqueIndex(uniqueIndex,
                "CREATE UNIQUE INDEX " + uniqueIndex + " ON " + table + "(account_id)");
        // account_id 欄是 runner 自己補的，Hibernate 要到下次啟動才會補 FK，這裡直接建齊
        createForeignKeyIfAbsent(table, "FK_" + table + "_account_id",
                "ALTER TABLE " + table + " ADD CONSTRAINT FK_" + table + "_account_id"
                        + " FOREIGN KEY (account_id) REFERENCES account(account_id)");
    }

    private void createForeignKeyIfAbsent(String table, String name, String ddl) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.foreign_keys
                    WHERE name = ? AND parent_object_id = OBJECT_ID(?)
                """, Integer.class, name, table);
        if (found != null && found > 0) {
            return;
        }
        jdbc.execute(ddl);
        log.info("已建立外鍵 {}", name);
    }

    private void dropIndexesOnColumn(String table, String column) {
        var indexes = jdbc.queryForList("""
                SELECT DISTINCT i.name AS name, i.is_unique_constraint AS is_constraint
                    FROM sys.indexes i
                    JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE i.object_id = OBJECT_ID(?) AND c.name = ?
                      AND i.name IS NOT NULL AND i.is_primary_key = 0
                """, table, column);
        for (var row : indexes) {
            String name = (String) row.get("name");
            if (Boolean.TRUE.equals(row.get("is_constraint"))) {
                jdbc.execute("ALTER TABLE [" + table + "] DROP CONSTRAINT [" + name + "]");
            } else {
                jdbc.execute("DROP INDEX [" + name + "] ON [" + table + "]");
            }
            log.info("已移除 {}.{} 上的索引 {}", table, column, name);
        }
    }

    /** 移除欄位上的 CHECK 或 DEFAULT 約束（兩張系統表結構相同，共用一段） */
    private void dropColumnConstraints(String table, String column, String catalogView) {
        var constraints = jdbc.queryForList("""
                SELECT cc.name AS name
                    FROM %s cc
                    JOIN sys.columns c ON c.object_id = cc.parent_object_id
                                      AND c.column_id = cc.parent_column_id
                    WHERE cc.parent_object_id = OBJECT_ID(?) AND c.name = ?
                """.formatted(catalogView), table, column);
        for (var row : constraints) {
            jdbc.execute("ALTER TABLE [" + table + "] DROP CONSTRAINT [" + row.get("name") + "]");
            log.info("已移除 {}.{} 上的約束 {}", table, column, row.get("name"));
        }
    }

    // === 課程域自商品域獨立 ===================================================
    // courses 不再掛 product_id，價格與堂數改存自己的欄位；
    // order_items 改為 product_id ⊕ course_id 雙 FK 多型，
    // 兩者恰好一個非空由 CHECK 約束把關（JPA 無法表達）。
    // products 的 type / sessions_per_unit 是課程寄生時期的欄位，一併移除。

    private void migrateCourseDomainSplit() {
        dropColumnIfExists("courses", "product_id");
        dropColumnIfExists("products", "type");
        dropColumnIfExists("products", "sessions_per_unit");

        // 私教課額度改連 courses：course_id 由 ddl-auto 新增，
        // 舊 product_id 連同 FK 移除，不做 id 對應（與本節其他遷移一致），
        // 歷史列靠 course_name 快照辨識課程。
        dropForeignKeysOnColumn("member_course_quota", "product_id");
        dropColumnIfExists("member_course_quota", "product_id");

        // 既有明細都是商品，課程 FK 為 NULL；product_id 由 NOT NULL 放寬為可空
        if (columnExists("order_items", "product_id")) {
            alterColumnNullable("order_items", "product_id", "int");
        }
        // 雙 FK 互斥 CHECK 由 splitCourseEntity() 以拆分後的欄名建立
    }

    // === 課程域拆分：courses → private_course／group_course =====================
    // 2026-08-13 定案：單一 Course 拆為私教課（可購買、綁教練）與團課
    //（憑訂閱參加、主檔綁預設教練，實際授課者以場次為準）。
    // 既有 courses 資料依 course_type 分流搬遷，
    // id 原值保留（IDENTITY_INSERT），指向它的 FK 欄改名後資料依然有效：
    // order_items.course_id → private_course_id（團課不可購買）
    // member_course_quota.course_id → private_course_id（額度僅來自私教課）
    // group_class_sessions.course_id → group_course_id（場次屬團課）

    private void splitCourseEntity() {
        // 1. 既有資料分流搬遷（id 保留原值，兩張新表的 id 空間彼此獨立不衝突）
        if (tableExists("courses")) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM courses", Integer.class);
            if (rows != null && rows > 0) {
                if (!columnExists("courses", "course_type")) {
                    log.warn("courses 有 {} 列但缺 course_type 欄，無法自動分流，請人工處理", rows);
                    return;
                }
                // SET IDENTITY_INSERT 與 INSERT 必須同一批次執行，走 execute 而非 update
                jdbc.execute("""
                        SET IDENTITY_INSERT private_course ON;
                        INSERT INTO private_course (private_course_id, name, description, image_url,
                                price, session_per_unit, trainer_id, course_category_id,
                                max_capacity, is_trial, status, created_at, updated_at)
                        SELECT course_id, name, description, image_url,
                                price, session_per_unit, trainer_id, course_category_id,
                                max_capacity, is_trial, status, created_at, updated_at
                            FROM courses c
                            WHERE c.course_type = 'PRIVATE'
                              AND NOT EXISTS (SELECT 1 FROM private_course p
                                                  WHERE p.private_course_id = c.course_id);
                        SET IDENTITY_INSERT private_course OFF;
                        """);
                log.info("已將 courses 的 PRIVATE 列分流至 private_course");
                jdbc.execute("""
                        SET IDENTITY_INSERT group_course ON;
                        INSERT INTO group_course (group_course_id, trainer_id, name, description, image_url,
                                course_category_id, max_capacity, is_trial,
                                status, created_at, updated_at)
                        SELECT course_id, trainer_id, name, description, image_url,
                                course_category_id, max_capacity, is_trial,
                                status, created_at, updated_at
                            FROM courses c
                            WHERE c.course_type = 'GROUP'
                              AND NOT EXISTS (SELECT 1 FROM group_course g
                                                  WHERE g.group_course_id = c.course_id);
                        SET IDENTITY_INSERT group_course OFF;
                        """);
                log.info("已將 courses 的 GROUP 列分流至 group_course（主檔綁預設教練、價格捨棄）");
            }
        }

        // 2. FK 欄位改名（renameColumnIfExists 會先拆掉 Hibernate 預建的空新欄）。
        //    order_items 的舊互斥 CHECK 綁著 course_id，改名前須先卸掉；
        //    只在改名尚未發生時動作，避免每次啟動都拆建一輪
        if (columnExists("order_items", "course_id")) {
            dropConstraintIfExists("order_items", "CK_oi_exactly_one_ref");
            renameColumnIfExists("order_items", "course_id", "private_course_id");
        }
        renameColumnIfExists("member_course_quota", "course_id", "private_course_id");
        renameColumnIfExists("group_class_sessions", "course_id", "group_course_id");

        // 3. 以拆分後的欄名重建互斥 CHECK
        if (columnExists("order_item", "private_course_id")) {
            createCheckIfAbsent("order_item", "CK_oi_exactly_one_ref", """
                    ALTER TABLE order_item ADD CONSTRAINT CK_oi_exactly_one_ref CHECK (
                        (product_id IS NOT NULL AND private_course_id IS NULL)
                     OR (product_id IS NULL AND private_course_id IS NOT NULL))
                    """);
        }

        // 4. 分流完成後移除舊表（FK 欄改名後仍指向 courses 的殘留外鍵一併拆掉，
        //    指向新表的 FK 由 ddl-auto 下次啟動補建）
        if (tableExists("courses")) {
            dropForeignKeysReferencing("courses");
            jdbc.execute("DROP TABLE courses");
            log.info("已移除舊 courses 表（資料已分流至 private_course／group_course）");
        }

        // 5. image_url 對齊 1000 字元（採課程模組組員的設計；ddl-auto 不會加寬既有欄位）
        widenNvarchar("group_course", "image_url", 1000);
    }

    /** 加寬 nvarchar 欄位（僅在現寬不足時動作；ddl-auto 的 update 模式不會改欄寬） */
    private void widenNvarchar(String table, String column, int length) {
        if (!tableExists(table) || !columnExists(table, column)) {
            return;
        }
        Integer maxLen = jdbc.queryForObject("""
                SELECT c.max_length FROM sys.columns c
                    JOIN sys.types t ON t.user_type_id = c.user_type_id
                    WHERE c.object_id = OBJECT_ID(?) AND c.name = ? AND t.name = 'nvarchar'
                """, Integer.class, table, column);
        // sys.columns 的 max_length 是位元組數（nvarchar 每字元 2 bytes）；-1 代表 MAX
        if (maxLen == null || maxLen == -1 || maxLen >= length * 2) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " nvarchar(" + length + ") NULL");
        log.info("已將 {}.{} 加寬為 nvarchar({})", table, column, length);
    }

    private void alterColumnNullable(String table, String column, String type) {
        Boolean nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM sys.columns
                    WHERE object_id = OBJECT_ID(?) AND name = ?
                """, Boolean.class, table, column);
        if (Boolean.TRUE.equals(nullable)) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " " + type + " NULL");
        log.info("已將 {}.{} 放寬為可空", table, column);
    }

    private void createCheckIfAbsent(String table, String constraint, String ddl) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.check_constraints
                    WHERE name = ? AND parent_object_id = OBJECT_ID(?)
                """, Integer.class, constraint, table);
        if (found != null && found > 0) {
            return;
        }
        jdbc.execute(ddl);
        log.info("已建立 CHECK 約束 {}", constraint);
    }

    // === 篩選唯一索引 =========================================================
    // 這些都帶 WHERE 條件，UNIQUE CONSTRAINT 語法上做不到，
    // 因此無法用 JPA 的 @UniqueConstraint 表達。

    private void createFilteredUniqueIndexes() {
        // 同一場團課、同一位會員只能有一筆有效報名。
        // 限定 status = 'BOOKED' 才能讓「取消後重新報名」成立。
        createFilteredUniqueIndex("UX_gcb_session_member", """
                CREATE UNIQUE INDEX UX_gcb_session_member
                    ON group_class_booking(session_id, member_id)
                    WHERE status = 'BOOKED'
                """);

        // 同一位教練、同一個開始時間只能有一個有效時段。
        // 已取消的時段應允許在同一時間重新開放，故排除 CANCELLED。
        // 註：只擋開始時間完全相同者，部分重疊仍須由 service 層檢查。
        createFilteredUniqueIndex("UX_coach_slot_trainer_start", """
                CREATE UNIQUE INDEX UX_coach_slot_trainer_start
                    ON coach_slot(trainer_id, start_at)
                    WHERE status <> 'CANCELLED'
                """);

        // 同一個時段，最多只能有一筆有效（非取消）的預約。
        // PENDING（已預約待上課）與 COMPLETED（已核銷）都算佔用中，
        // 只有 CANCELLED 才釋放這個時段，讓別的會員可以重新預約。
        // 這是應用層樂觀鎖（CoachSlot.version）之外，資料庫層獨立的第二道防護，
        // 就算應用層邏輯有漏洞，也不可能真的塞進兩筆有效預約。
        createFilteredUniqueIndex("UX_coach_booking_active_slot", """
                CREATE UNIQUE INDEX UX_coach_booking_active_slot
                    ON coach_booking(coach_slot_id)
                    WHERE status <> 'CANCELLED'
                """);

        // 一張已領取的優惠券只能用在一筆訂單。
        // 需要 WHERE 是因為大多數訂單沒用券，而 SQL Server 的唯一索引
        // 只允許「一列」為 NULL，不加條件會在第二筆無券訂單就爆掉。
        dropIndexIfExists("orders", "UX_orders_user_coupon");
        createFilteredUniqueIndex("UX_orders_member_coupon", """
                CREATE UNIQUE INDEX UX_orders_member_coupon
                    ON orders(member_coupon_id)
                    WHERE member_coupon_id IS NOT NULL
                """);

        // 同一會員同一天只能有一筆有效的 InBody 記錄（欄位已改名 member_id）。
        // 本表採軟刪除，限定 deleted_at IS NULL 才能讓「刪除後重新上傳」成立。
        // 早期版本以一般 UNIQUE CONSTRAINT／user_id 舊欄名表達，先清掉。
        dropConstraintIfExists("inbody_record", "UQ_inbody_user_date");
        dropIndexIfExists("inbody_record", "UX_inbody_user_date");
        createFilteredUniqueIndex("UX_inbody_member_date", """
                CREATE UNIQUE INDEX UX_inbody_member_date
                    ON inbody_record(member_id, update_date)
                    WHERE deleted_at IS NULL
                """);
    }

    // === 聊天未讀篩選索引 =====================================================
    // 未讀數查詢只關心 is_read = 0 的列，篩選索引可大幅縮小掃描範圍；
    // 帶 WHERE 條件，JPA @Index 無法表達，因此放在這裡建立。

    private void createChatUnreadIndexes() {
        createFilteredIndex("IX_chat_unread_member", """
                CREATE INDEX IX_chat_unread_member
                    ON chat_message(member_id, is_read)
                    WHERE is_read = 0
                """);
        createFilteredIndex("IX_chat_unread_emp", """
                CREATE INDEX IX_chat_unread_emp
                    ON chat_message(employee_id, is_read)
                    WHERE is_read = 0
                """);
    }

    // === AI 課表外鍵 ==========================================================
    // ai_health_plan.inbody_record_id 在 entity 只是純整數欄位（刻意不建 JPA
    // 關聯），ddl-auto 不會自動生成這個外鍵，必須自己補。

    private void addForeignKeys() {
        addForeignKeyIfMissing("FK_plan_inbody", """
                ALTER TABLE ai_health_plan
                    ADD CONSTRAINT FK_plan_inbody FOREIGN KEY (inbody_record_id)
                        REFERENCES inbody_record(id)
                """);
    }

    // === email_verification.purpose 補欄（feat/account-system）================
    // entity 宣告 nullable=false，但 SQL Server 不允許對非空表直接加 NOT NULL
    // 欄位——ddl-auto 的 ALTER 會失敗且只默默記 log，欄位根本不存在，
    // 註冊／重寄在執行期直接 500。這裡以「可空」補上欄位即可：
    // 新程式寫入時一定帶 purpose；遷移前的舊列維持 NULL，
    // verifyEmail() 對 null 已有防呆（視為連結失效，引導重寄），不做回填。
    // 全新資料庫由 ddl-auto 直接建出 NOT NULL 版本，本方法會因欄位已存在而略過。

    private void addEmailVerificationPurposeColumn() {
        if (!tableExists("email_verification") || columnExists("email_verification", "purpose")) {
            return;
        }
        jdbc.execute("ALTER TABLE email_verification ADD purpose varchar(20) NULL");
        log.info("已為 email_verification 補上 purpose 欄位（可空；舊列 NULL＝連結失效）");
    }

    // === 既有資料回填 =========================================================
    // ddl-auto=update 新增欄位時一律建成 nullable，既有列會是 NULL。
    // @Version 欄位若為 NULL，Hibernate 更新該實體時會出錯，必須補 0。
    // 資料庫是空的時候這些 UPDATE 影響 0 列，不會有副作用。

    private void backfillExistingRows() {
        // 所有帶 @Version 的表都要補；漏一張，該表的舊列第一次被更新時就會炸
        for (String table : new String[] {
                "member_course_quota", "group_class_session", "orders", "cart_item",
                "payment_transaction", "payment_refund", "product" }) {
            if (columnExists(table, "version")) {
                backfill(table + ".version",
                        "UPDATE " + table + " SET version = 0 WHERE version IS NULL");
            }
        }

        backfill("group_class_session.booked_count",
                "UPDATE group_class_session SET booked_count = 0 WHERE booked_count IS NULL");

        // 場次容量沿用所屬團課的預設值（課程域拆分後場次改指 group_course）
        if (columnExists("group_class_session", "group_course_id") && tableExists("group_course")) {
            backfill("group_class_session.capacity", """
                    UPDATE s SET s.capacity = c.max_capacity
                        FROM group_class_session s
                        JOIN group_course c ON c.group_course_id = s.group_course_id
                        WHERE s.capacity IS NULL
                    """);
        }
    }

    // === OAuth 綁定回歸 member_oauth_account ==================================
    // member 表上的 oauth_provider / oauth_sub 是整併重複 entity 時混入的
    // 第二套設計，缺唯一約束。綁定資料遷回 member_oauth_account
    //（有複合唯一約束）後，移除 member 上的兩個欄位。
    // 註：若歷史資料中兩個會員共用同一組 (provider, sub)，只有第一筆會遷入，
    // 其餘略過 —— 那本來就是缺唯一約束造成的髒資料，遷移量請看啟動 log。

    private void migrateOauthBindingsToAccountTable() {
        if (columnExists("member", "oauth_sub")) {
            backfill("member_oauth_account", """
                    INSERT INTO member_oauth_account (member_id, oauth_provider, provider_user_id, created_at)
                    SELECT u.member_id, u.oauth_provider, u.oauth_sub, SYSDATETIME()
                        FROM member u
                        WHERE u.oauth_sub IS NOT NULL AND u.oauth_provider IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM member_oauth_account a
                                              WHERE a.oauth_provider = u.oauth_provider
                                                AND a.provider_user_id = u.oauth_sub)
                    """);
        }
        dropColumnIfExists("member", "oauth_provider");
        dropColumnIfExists("member", "oauth_sub");
    }

    // === OAuth 綁定改掛 account（2026-08-13 跟進 feat/member-register-verify）==
    // OAuth 是登入方式，屬帳號域：綁定表由 member_oauth_account（member_id FK）
    // 改為 oauth_account（account_id FK），員工帳號同樣能走 OAuth 登入。
    // 舊綁定以 member.account_id 換算；換算不到的孤兒綁定直接刪除
    //（沒有對應會員的綁定本來就登不進任何帳號）。

    private void rebindOauthToAccount() {
        if (tableExists("member_oauth_account")) {
            if (columnExists("member_oauth_account", "member_id") && columnExists("member", "account_id")) {
                if (!columnExists("member_oauth_account", "account_id")) {
                    jdbc.execute("ALTER TABLE member_oauth_account ADD account_id int NULL");
                    log.info("已為 member_oauth_account 補上 account_id 欄位");
                }
                backfill("member_oauth_account.account_id", """
                        UPDATE moa SET account_id = m.account_id
                            FROM member_oauth_account moa JOIN member m ON m.member_id = moa.member_id
                            WHERE moa.account_id IS NULL
                        """);
                backfill("member_oauth_account（清除孤兒綁定）",
                        "DELETE FROM member_oauth_account WHERE account_id IS NULL");
                dropColumnIfExists("member_oauth_account", "member_id");
            }
            renameColumnIfExists("member_oauth_account", "id", "oauth_account_id");
            if (renameLegacyTable("member_oauth_account", "oauth_account")) {
                renameUniqueIndex("oauth_account", "UX_user_oauth_accounts_provider", "UX_oauth_account_provider");
            }
        }
        // 收緊（冪等）：account_id 無 NULL 才收 NOT NULL，FK 名稱與 entity 宣告一致
        if (tableExists("oauth_account") && columnExists("oauth_account", "account_id")) {
            Integer nulls = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM oauth_account WHERE account_id IS NULL", Integer.class);
            if (nulls != null && nulls == 0) {
                Boolean nullable = jdbc.queryForObject("""
                        SELECT is_nullable FROM sys.columns
                            WHERE object_id = OBJECT_ID('oauth_account') AND name = 'account_id'
                        """, Boolean.class);
                if (Boolean.TRUE.equals(nullable)) {
                    jdbc.execute("ALTER TABLE oauth_account ALTER COLUMN account_id int NOT NULL");
                    log.info("已將 oauth_account.account_id 收為 NOT NULL");
                }
                createForeignKeyIfAbsent("oauth_account", "FK_oauth_account_account_id",
                        "ALTER TABLE oauth_account ADD CONSTRAINT FK_oauth_account_account_id"
                                + " FOREIGN KEY (account_id) REFERENCES account(account_id)");
            }
        }
    }

    // === 清除已淘汰的欄位 =====================================================
    // Hibernate 的 update 模式永遠不會刪欄位，只能自己來。

    private void dropObsoleteColumns() {
        dropColumnIfExists("orders", "coupon_id");
        dropColumnIfExists("order_item", "line_amount");
        // 認證三表改綁 account（會員／員工共用登入帳號），舊的 member_id
        // 是 NOT NULL 且程式不再填值，不清掉的話新資料一律插入失敗
        dropColumnIfExists("email_verification", "member_id");
        dropColumnIfExists("password_reset_token", "member_id");
        dropColumnIfExists("password_history", "member_id");
        // 團課報名資格決議只看 status（體驗課由 is_trial 判斷），訂閱開關欄位捨棄
        dropColumnIfExists("group_course", "is_open_for_subscription");
    }

    // === 訂單狀態值域擴充（新增 REFUNDED）======================================
    // orders.status 上有一個 Hibernate 建表當時自動產生、雜湊命名的 CHECK 約束，
    // 值域鎖死當時的 OrderStatus 列舉值。ddl-auto=update 不會回頭更新既有表的
    // CHECK 約束，新增 REFUNDED 前必須手動換成涵蓋新值域、具名可辨識的版本，
    // 否則退款完成時的狀態更新會直接違反約束。

    private void widenOrderStatusCheck() {
        dropOtherCheckConstraintsOnColumn("orders", "status", "CK_orders_status");
        createCheckIfAbsent("orders", "CK_orders_status", """
                ALTER TABLE orders ADD CONSTRAINT CK_orders_status CHECK (
                    status IN ('UNPAID','PAID','SHIPPED','COMPLETED','CANCELLED','FAILED','REFUNDED'))
                """);
    }

    private void dropOtherCheckConstraintsOnColumn(String table, String column, String keepName) {
        var checks = jdbc.queryForList("""
                SELECT cc.name AS name
                    FROM sys.check_constraints cc
                    WHERE cc.parent_object_id = OBJECT_ID(?)
                      AND cc.parent_column_id = COLUMNPROPERTY(OBJECT_ID(?), ?, 'ColumnId')
                      AND cc.name <> ?
                """, table, table, column, keepName);
        for (var row : checks) {
            jdbc.execute("ALTER TABLE [" + table + "] DROP CONSTRAINT [" + row.get("name") + "]");
            log.info("已移除 {}.{} 上的舊 CHECK 約束 {}", table, column, row.get("name"));
        }
    }

    // === 清除重複的唯一索引 ===================================================
    // 這些欄位曾同時以具名 @UniqueConstraint 與 unique=true 宣告，
    // Hibernate 因此各建了兩個功能相同的唯一約束。unique=true 已自
    // entity 移除，這裡清掉它先前建出的那個（名稱是 Hibernate 產生的 UK_ 開頭
    // 雜湊，無法寫死，改由 sys.indexes 動態找出具名約束以外、
    // 只含該單欄的唯一索引/約束）。

    private void dropDuplicateUniqueIndexes() {
        dropDuplicateUnique("member", "email", "UX_member_email");
        dropDuplicateUnique("email_verification", "token", "UX_email_verification_token");
        dropDuplicateUnique("password_reset_token", "token", "UX_password_reset_token_token");
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

    private void createFilteredIndex(String indexName, String ddl) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.indexes WHERE name = ?", Integer.class, indexName);
        if (found != null && found > 0) {
            log.debug("索引 {} 已存在，略過", indexName);
            return;
        }
        jdbc.execute(ddl);
        log.info("已建立篩選索引 {}", indexName);
    }

    private void addForeignKeyIfMissing(String constraintName, String ddl) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.foreign_keys WHERE name = ?", Integer.class, constraintName);
        if (found != null && found > 0) {
            log.debug("外鍵 {} 已存在，略過", constraintName);
            return;
        }
        jdbc.execute(ddl);
        log.info("已建立外鍵 {}", constraintName);
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

    /** 先拆掉欄位上所有依附物（FK／索引／CHECK／DEFAULT，任一都會以 5074 擋 DROP）再移除欄位 */
    private void dropColumnIfExists(String table, String column) {
        if (!tableExists(table) || !columnExists(table, column)) {
            return;
        }
        dropForeignKeysOnColumn(table, column);
        dropIndexesOnColumn(table, column);
        dropColumnConstraints(table, column, "sys.check_constraints");
        dropColumnConstraints(table, column, "sys.default_constraints");
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
