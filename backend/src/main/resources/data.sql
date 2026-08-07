-- ---------------------------------------------------------------------------
-- Hibernate ddl-auto 做不到的 schema 設定
--
-- 執行時機：每次啟動、Hibernate 建完表之後
--   （由 spring.jpa.defer-datasource-initialization=true 保證順序，
--     spring.sql.init.mode=always 讓非嵌入式資料庫也會執行）
--
-- 規則：這裡所有敘述都必須是冪等的（重複執行安全），因為每次啟動都會跑。
--       一次性的資料遷移不要放這裡。
--
-- 注意：SQL Server 不支援 CREATE INDEX IF NOT EXISTS，只能查 sys.indexes；
--       也不要寫 GO，Spring 是以分號切敘述，不認得批次分隔符。
-- ---------------------------------------------------------------------------


-- === 篩選唯一索引 =========================================================
-- 這三個都帶 WHERE 條件，UNIQUE CONSTRAINT 語法上做不到，
-- 因此無法用 JPA 的 @UniqueConstraint 表達。

-- 同一場團課、同一位會員只能有一筆有效報名。
-- 限定 status = 'BOOKED' 才能讓「取消後重新報名」成立。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_gcb_session_member')
    CREATE UNIQUE INDEX UX_gcb_session_member
        ON group_class_bookings(session_id, member_id)
        WHERE status = 'BOOKED';

-- 同一位教練、同一個開始時間只能有一個有效時段。
-- 已取消的時段應允許在同一時間重新開放，故排除 CANCELLED。
-- 註：只擋開始時間完全相同者，部分重疊仍須由 service 層檢查。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_coach_slots_trainer_start')
    CREATE UNIQUE INDEX UX_coach_slots_trainer_start
        ON coach_slots(trainer_id, start_at)
        WHERE status <> 'CANCELLED';

-- 一張已領取的優惠券只能用在一筆訂單。
-- 需要 WHERE 是因為大多數訂單沒用券，而 SQL Server 的唯一索引
-- 只允許「一列」為 NULL，不加條件會在第二筆無券訂單就爆掉。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_orders_user_coupon')
    CREATE UNIQUE INDEX UX_orders_user_coupon
        ON orders(user_coupon_id)
        WHERE user_coupon_id IS NOT NULL;


-- === 既有資料回填 =========================================================
-- ddl-auto=update 新增欄位時一律建成 nullable，既有列會是 NULL。
-- @Version 欄位若為 NULL，Hibernate 更新該實體時會出錯，必須補 0。
-- 資料庫是空的時候這些 UPDATE 影響 0 列，不會有副作用。

UPDATE user_course_quota SET version = 0 WHERE version IS NULL;

UPDATE group_class_sessions SET version = 0 WHERE version IS NULL;
UPDATE group_class_sessions SET booked_count = 0 WHERE booked_count IS NULL;

-- 場次容量沿用所屬課程的預設值
UPDATE s SET s.capacity = c.max_capacity
    FROM group_class_sessions s
    JOIN courses c ON c.id = s.course_id
    WHERE s.capacity IS NULL;


-- === 清除已淘汰的欄位 =====================================================
-- orders.coupon_id 已由 orders.user_coupon_id 取代（改指向 user_coupons，
-- 才能標記「這張券用掉了」並保證一張券只用一次）。
-- Hibernate 的 update 模式永遠不會刪欄位，只能自己來。

IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID('orders') AND name = 'coupon_id')
    ALTER TABLE orders DROP COLUMN coupon_id;
