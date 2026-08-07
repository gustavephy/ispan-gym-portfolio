/* =============================================================================
 * 威力健身房管理系統 — 完整資料庫建置腳本（DDL）
 * =============================================================================
 * 內容：19 張表（17 核心 + 2 優惠券）+ 系統帳號 seed
 * DB：  Microsoft SQL Server 2016+（AT TIME ZONE 需 2016 以上）
 *
 * 這是 V1 + V3 + V2 三個 Flyway migration 的合併版，供一次建好整個資料庫。
 * （專案內仍以 V1/V2/V3 分檔由 Flyway 版本控管；此為交付／組員手動建庫用的整合版）
 *
 * ── 執行順序 ──
 *   1. 先在 SSMS 建空庫：  CREATE DATABASE wpower_gym;
 *   2. USE wpower_gym; 然後執行本檔
 *
 * ⚠️ 若 AT TIME ZONE 在 DEFAULT 裡被拒絕：
 *   全檔搜尋取代：
 *     CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)
 *     →  SYSDATETIME()
 *   部署前確認時區名稱：
 *     SELECT * FROM sys.time_zone_info WHERE name LIKE '%Taipei%';
 *
 * ⚠️ 時區：所有 datetime2 一律台北時間 (UTC+8)；JVM 層為主力防線。
 * =============================================================================
 */

/* ═══════════════════════════════════════════════════════════════════════
 * PART 1：核心 17 張表
 * ═══════════════════════════════════════════════════════════════════════ */

CREATE TABLE users (
    id          BIGINT        IDENTITY(1,1) NOT NULL,
    first_name  NVARCHAR(50)  NOT NULL,                      -- 名（原 surname）
    last_name   NVARCHAR(50)  NOT NULL,                      -- 姓（原 lastname）
    dob         DATE              NULL,                      -- AI 健管推算年齡
    email       VARCHAR(100)  NOT NULL,
    phone       VARCHAR(20)       NULL,                      -- 容納市話分機與 +886
    gender      VARCHAR(10)       NULL,                      -- AI 健管計算 BMR
    role        VARCHAR(20)   NOT NULL,
    pwd_hash    VARCHAR(255)      NULL,                      -- BCrypt。OAuth 使用者無密碼 → nullable
    oauth_provider VARCHAR(20)    NULL,                      -- 'GOOGLE'（Slide 9）
    oauth_sub      VARCHAR(255)   NULL,                      -- ★ OAuth 穩定使用者 ID。email 會變，sub 不會
    status      VARCHAR(20)   NOT NULL
        CONSTRAINT DF_users_status DEFAULT ('ACTIVE'),       -- 註冊即啟用
    created_at  DATETIME2     NOT NULL
        CONSTRAINT DF_users_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at  DATETIME2     NOT NULL
        CONSTRAINT DF_users_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_users        PRIMARY KEY (id),
    CONSTRAINT CK_users_role   CHECK (role   IN ('MEMBER','TRAINER','ADMIN')),
    CONSTRAINT CK_users_status CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT CK_users_gender CHECK (gender IN ('M','F','OTHER')),
    CONSTRAINT CK_users_oauth_provider CHECK (oauth_provider IN ('GOOGLE')),
    -- ★ 每個人至少有一種登入方式（密碼 或 OAuth）
    CONSTRAINT CK_users_auth CHECK (pwd_hash IS NOT NULL OR oauth_sub IS NOT NULL),
    -- ★ 兩欄同進同出
    CONSTRAINT CK_users_oauth CHECK (
        (oauth_provider IS NULL     AND oauth_sub IS NULL)
        OR
        (oauth_provider IS NOT NULL AND oauth_sub IS NOT NULL)
    )
);
GO

CREATE TABLE courses (
    id                       BIGINT        IDENTITY(1,1) NOT NULL,
    name                     NVARCHAR(50)  NOT NULL,         -- 一對一私教／瑜珈／TRX／飛輪
    description              NVARCHAR(500)     NULL,
    default_duration_minutes INT           NOT NULL
        CONSTRAINT DF_courses_duration DEFAULT (60),         -- 僅為建場次時的預設值
    default_capacity         INT           NOT NULL,         -- 1=私教, 20=團課。僅為預設值
    status                   VARCHAR(20)   NOT NULL
        CONSTRAINT DF_courses_status DEFAULT ('ACTIVE'),
    created_at               DATETIME2     NOT NULL
        CONSTRAINT DF_courses_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at               DATETIME2     NOT NULL
        CONSTRAINT DF_courses_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_courses          PRIMARY KEY (id),
    CONSTRAINT CK_courses_duration CHECK (default_duration_minutes > 0),
    CONSTRAINT CK_courses_capacity CHECK (default_capacity >= 1),
    CONSTRAINT CK_courses_status   CHECK (status IN ('ACTIVE','INACTIVE'))
);
GO

CREATE TABLE rooms (
    id         BIGINT        IDENTITY(1,1) NOT NULL,
    name       NVARCHAR(50)  NOT NULL,                       -- A 教室／飛輪室／重訓區
    capacity   INT           NOT NULL,                       -- 實體容納上限
    status     VARCHAR(20)   NOT NULL
        CONSTRAINT DF_rooms_status DEFAULT ('ACTIVE'),
    created_at DATETIME2     NOT NULL
        CONSTRAINT DF_rooms_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at DATETIME2     NOT NULL
        CONSTRAINT DF_rooms_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_rooms          PRIMARY KEY (id),
    CONSTRAINT CK_rooms_capacity CHECK (capacity >= 1),
    CONSTRAINT CK_rooms_status   CHECK (status IN ('ACTIVE','INACTIVE'))
);
GO


/* =============================================================================
 * 2. 第一層相依
 * ========================================================================== */

CREATE TABLE trainer_details (
    id         BIGINT         IDENTITY(1,1) NOT NULL,
    user_id    BIGINT         NOT NULL,                      -- NOT NULL + UNIQUE = 1:1 保證
    experience NVARCHAR(MAX)      NULL,
    specialty  NVARCHAR(255)      NULL,
    bio        NVARCHAR(MAX)      NULL,
    created_at DATETIME2      NOT NULL
        CONSTRAINT DF_td_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at DATETIME2      NOT NULL
        CONSTRAINT DF_td_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_trainer_details      PRIMARY KEY (id),
    CONSTRAINT UQ_trainer_details_user UNIQUE (user_id)      -- ★ 1:1 靠這條
);
GO

CREATE TABLE products (
    id             BIGINT          IDENTITY(1,1) NOT NULL,
    type           VARCHAR(20)     NOT NULL,
    course_id      BIGINT              NULL,                 -- 僅 type='COURSE'
    name           NVARCHAR(50)    NOT NULL,                 -- 方案名，如「瑜珈 10 堂」
    description    NVARCHAR(1000)      NULL,
    image_url      VARCHAR(500)        NULL,
    price          DECIMAL(10,2)   NOT NULL,
    stock          BIGINT          NOT NULL
        CONSTRAINT DF_products_stock DEFAULT (0),            -- 僅 type='GOODS'
    sessions_count INT                 NULL,                 -- 僅 type='COURSE'
    validity_days  INT                 NULL,                 -- 僅 type='COURSE'
    version        INT             NOT NULL
        CONSTRAINT DF_products_version DEFAULT (0),          -- Hibernate @Version 樂觀鎖
    status         VARCHAR(20)     NOT NULL
        CONSTRAINT DF_products_status DEFAULT ('ACTIVE'),
    created_at     DATETIME2       NOT NULL
        CONSTRAINT DF_products_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at     DATETIME2       NOT NULL
        CONSTRAINT DF_products_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_products        PRIMARY KEY (id),
    CONSTRAINT CK_products_type   CHECK (type   IN ('COURSE','GOODS')),
    CONSTRAINT CK_products_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT CK_products_price  CHECK (price >= 0),
    CONSTRAINT CK_products_stock  CHECK (stock >= 0),
    -- ★ 單表繼承的型別完整性：課程必有課種/堂數/效期，商品必無
    CONSTRAINT CK_products_shape  CHECK (
        (type = 'COURSE' AND course_id IS NOT NULL
                         AND sessions_count IS NOT NULL AND validity_days IS NOT NULL)
        OR
        (type = 'GOODS'  AND course_id IS NULL
                         AND sessions_count IS NULL     AND validity_days IS NULL)
    )
);
GO

CREATE TABLE trainer_schedules (
    id            BIGINT     IDENTITY(1,1) NOT NULL,
    trainer_id    BIGINT     NOT NULL,
    course_id     BIGINT     NOT NULL,                       -- 這場開什麼課
    room_id       BIGINT         NULL,                       -- NULL = 不佔固定教室（私教在開放區域）
    start_at      DATETIME2  NOT NULL,                       -- 絕對時點，可跨日
    end_at        DATETIME2  NOT NULL,
    -- ★ 歸屬日＝開始日。PERSISTED 計算欄位，無法造假
    schedule_date AS (CAST(start_at AS DATE)) PERSISTED,
    capacity      INT        NOT NULL,                       -- Service 設為 MIN(課種預設, 教室上限)
    booked_count  INT        NOT NULL
        CONSTRAINT DF_sched_booked DEFAULT (0),              -- 冗餘計數，見字典附錄 C
    is_available  BIT        NOT NULL
        CONSTRAINT DF_sched_available DEFAULT (1),           -- 教練是否開放（≠ 是否被訂）
    created_at    DATETIME2  NOT NULL
        CONSTRAINT DF_sched_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_trainer_schedules PRIMARY KEY (id),
    CONSTRAINT CK_schedule_time     CHECK (end_at > start_at),
    CONSTRAINT CK_schedule_capacity CHECK (capacity >= 1),
    -- ★ 名額兜底：即使程式寫錯，也不可能超收
    CONSTRAINT CK_schedule_booked   CHECK (booked_count BETWEEN 0 AND capacity)
);
GO
-- 註：v8 曾有 CK_schedule_hourly（整點 + 60 分）。課種長度不一後移除
--     —— CHECK 跨不了表，讀不到 courses.default_duration_minutes。
--     範圍重疊改由 Service 層檢查，見《實作指引》§6。

CREATE TABLE orders (
    id                BIGINT         IDENTITY(1,1) NOT NULL,
    user_id           BIGINT         NOT NULL,
    total_amount      DECIMAL(10,2)  NOT NULL,
    payment_method    VARCHAR(20)        NULL,
    merchant_trade_no VARCHAR(20)    NOT NULL,               -- ★ 綠界上限 20 碼
    trade_no          VARCHAR(20)        NULL,               -- ★ 綠界交易編號，同為 20 碼
    status            VARCHAR(20)    NOT NULL
        CONSTRAINT DF_orders_status DEFAULT ('UNPAID'),
    paid_at           DATETIME2          NULL,
    receiver_name     NVARCHAR(50)       NULL,               -- 純課程訂單為 NULL
    receiver_phone    VARCHAR(20)        NULL,
    receiver_address  NVARCHAR(200)      NULL,
    created_at        DATETIME2      NOT NULL
        CONSTRAINT DF_orders_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at        DATETIME2      NOT NULL
        CONSTRAINT DF_orders_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_orders        PRIMARY KEY (id),
    CONSTRAINT UQ_orders_mtn    UNIQUE (merchant_trade_no),  -- NOT NULL，故可用普通 UNIQUE
    CONSTRAINT CK_orders_status CHECK (status IN ('UNPAID','PAID','SHIPPED','COMPLETED','CANCELLED','FAILED')),
    CONSTRAINT CK_orders_amount CHECK (total_amount >= 0)
);
GO

CREATE TABLE cart_items (
    id         BIGINT     IDENTITY(1,1) NOT NULL,
    user_id    BIGINT     NOT NULL,
    product_id BIGINT     NOT NULL,
    quantity   BIGINT     NOT NULL
        CONSTRAINT DF_cart_quantity DEFAULT (1),
    created_at DATETIME2  NOT NULL
        CONSTRAINT DF_cart_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at DATETIME2  NOT NULL                           -- ★ 跨裝置同步的 Last-Write-Wins 依據
        CONSTRAINT DF_cart_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_cart_items    PRIMARY KEY (id),
    CONSTRAINT CK_cart_quantity CHECK (quantity >= 1)
);
GO

CREATE TABLE chat_messages (
    id              BIGINT         IDENTITY(1,1) NOT NULL,
    room_id         VARCHAR(64)    NOT NULL,                 -- ⚠️ 與 rooms 表無關！聊天室房間鍵 min(uid)_max(uid)
    sender_id       BIGINT         NOT NULL,
    receiver_id     BIGINT         NOT NULL,
    message_content NVARCHAR(500)  NOT NULL,
    is_read         BIT            NOT NULL
        CONSTRAINT DF_chat_is_read DEFAULT (0),
    read_at         DATETIME2          NULL,
    created_at      DATETIME2      NOT NULL
        CONSTRAINT DF_chat_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_chat_messages PRIMARY KEY (id),
    CONSTRAINT CK_chat_not_self CHECK (sender_id <> receiver_id)
);
GO

CREATE TABLE inbody_records (
    id          BIGINT        IDENTITY(1,1) NOT NULL,
    user_id     BIGINT        NOT NULL,
    record_date DATE          NOT NULL,
    height      DECIMAL(5,2)      NULL,                      -- cm（原 float，近似型有精度誤差）
    weight      DECIMAL(5,2)      NULL,                      -- kg
    body_fat    DECIMAL(5,2)      NULL,                      -- %
    muscle_mass DECIMAL(5,2)      NULL,                      -- kg
    bmi         DECIMAL(5,2)      NULL,                      -- 反正規化（可由 weight/(height/100)² 導出）
    created_at  DATETIME2     NOT NULL
        CONSTRAINT DF_inbody_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_inbody_records  PRIMARY KEY (id),
    CONSTRAINT CK_inbody_height   CHECK (height      BETWEEN 50 AND 250),
    CONSTRAINT CK_inbody_weight   CHECK (weight      BETWEEN 20 AND 300),
    CONSTRAINT CK_inbody_body_fat CHECK (body_fat    BETWEEN 0  AND 100),
    CONSTRAINT CK_inbody_muscle   CHECK (muscle_mass BETWEEN 0  AND 150)
);
GO
-- 替代方案：把 bmi 改成計算欄位，讓它無法造假
--   ALTER TABLE inbody_records DROP COLUMN bmi;
--   ALTER TABLE inbody_records ADD bmi AS (weight / POWER(height / 100.0, 2)) PERSISTED;

CREATE TABLE ai_health_plans (
    id                BIGINT         IDENTITY(1,1) NOT NULL,
    user_id           BIGINT         NOT NULL,
    inbody_record_id  BIGINT         NOT NULL,               -- 課表所依據的量測
    goal              VARCHAR(20)    NOT NULL,
    plan_content      NVARCHAR(MAX)  NOT NULL,               -- GPT-4o-mini 產出，建議存 JSON
    model_name        VARCHAR(50)    NOT NULL
        CONSTRAINT DF_plan_model DEFAULT ('gpt-4o-mini'),
    prompt_tokens     INT                NULL,               -- 成本追蹤
    completion_tokens INT                NULL,
    pdf_url           VARCHAR(500)       NULL,               -- NULL = 尚未產生
    created_at        DATETIME2      NOT NULL
        CONSTRAINT DF_plan_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_ai_health_plans PRIMARY KEY (id),
    CONSTRAINT CK_plan_goal       CHECK (goal IN ('FAT_LOSS','MUSCLE_GAIN','MAINTAIN'))
);
GO


/* =============================================================================
 * 3. 第二層相依：order_items → member_packages → reservations → logs
 * ========================================================================== */

CREATE TABLE order_items (
    id                  BIGINT         IDENTITY(1,1) NOT NULL,
    order_id            BIGINT         NOT NULL,
    product_id          BIGINT         NOT NULL,
    product_name_at_buy NVARCHAR(50)   NOT NULL,             -- ★ 商品名稱快照（原本只快照價格）
    price_at_buy        DECIMAL(10,2)  NOT NULL,             -- 歷史售價快照
    quantity            BIGINT         NOT NULL,             -- 課程：total_sessions = sessions_count × quantity
    created_at          DATETIME2      NOT NULL
        CONSTRAINT DF_oi_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_order_items     PRIMARY KEY (id),
    CONSTRAINT CK_order_items_qty CHECK (quantity >= 1)
);
GO

CREATE TABLE member_packages (
    id             BIGINT       IDENTITY(1,1) NOT NULL,
    user_id        BIGINT       NOT NULL,
    order_item_id  BIGINT       NOT NULL,                    -- ★ 一筆明細開一包課；兼綠界冪等第二防線
    course_id      BIGINT       NOT NULL,                    -- 快照自 products.course_id
    total_sessions INT          NOT NULL,                    -- 快照 = sessions_count × order_items.quantity
    -- ★ 預留制：total = reserved + used + remaining
    reserved_sessions INT       NOT NULL
        CONSTRAINT DF_pkg_reserved DEFAULT (0),              -- 已訂未核銷（占位）。冗餘計數
    used_sessions  INT          NOT NULL
        CONSTRAINT DF_pkg_used DEFAULT (0),                  -- 已消耗 = 核銷 + 罰扣 + 到期沒收。冗餘計數
    -- ＝還能訂幾堂
    remaining_sessions AS (total_sessions - reserved_sessions - used_sessions) PERSISTED,
    start_date     DATE         NOT NULL,                    -- ＝ orders.paid_at 的日期
    expire_date    DATE         NOT NULL,                    -- ＝ start_date + products.validity_days
    status         VARCHAR(20)  NOT NULL
        CONSTRAINT DF_pkg_status DEFAULT ('ACTIVE'),
    created_at     DATETIME2    NOT NULL
        CONSTRAINT DF_pkg_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at     DATETIME2    NOT NULL
        CONSTRAINT DF_pkg_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_member_packages     PRIMARY KEY (id),
    CONSTRAINT UQ_packages_order_item UNIQUE (order_item_id),  -- ★ 冪等第二防線
    CONSTRAINT CK_packages_status     CHECK (status IN ('ACTIVE','EXPIRED','REFUNDED')),
    -- ★ 堂數兜底：程式寫錯時 DB 也不允許超訂／超扣
    CONSTRAINT CK_packages_balance    CHECK (
        reserved_sessions >= 0
        AND used_sessions >= 0
        AND reserved_sessions + used_sessions <= total_sessions
    ),
    CONSTRAINT CK_packages_expire     CHECK (expire_date >= start_date)
);
GO
-- 註：無 'USED_UP' —— 可由 remaining_sessions = 0 導出，而它是計算欄位、自我維護。
--     'REFUNDED' 保留：不可導出，是真正獨立的狀態，只是流程未實作。
--
-- ★ 預留制（Mindbody 模型）：
--     訂課       → reserved +1          （remaining −1，寫 DEDUCT log）
--     核銷/罰扣  → reserved −1, used +1 （remaining 不變，不寫 log）
--     準時取消   → reserved −1          （remaining +1，寫 REVERT log）
--     到期沒收   → used += remaining    （reserved 不動，寫 EXPIRE log）
--   「銷課」＝訂課當下扣堂數（記 log）；「核銷」＝履約確認（記 reservations.check_in_time）

CREATE TABLE reservations (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    schedule_id   BIGINT         NOT NULL,                   -- 教練／課種／教室／時段由此 JOIN
    member_id     BIGINT         NOT NULL,
    package_id    BIGINT         NOT NULL,                   -- 無不扣堂數的課
    check_in_key  CHAR(8)            NULL,                   -- ★ 學員端產生的隨機 8 碼，交教練輸入核銷（Slide 15）
                                                            --   建議 Crockford Base32（去掉 I/L/O/U），教練要用手打
    check_in_time DATETIME2          NULL,                   -- ★ 銷課爭議關鍵證據
    status        VARCHAR(20)    NOT NULL
        CONSTRAINT DF_res_status DEFAULT ('PENDING'),
    cancel_type   VARCHAR(20)        NULL,                   -- ★ 誰取消的（可查、可統計）
    cancelled_by  BIGINT             NULL,
    cancel_reason NVARCHAR(200)      NULL,
    cancelled_at  DATETIME2          NULL,
    created_at    DATETIME2      NOT NULL
        CONSTRAINT DF_res_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_reservations PRIMARY KEY (id),
    CONSTRAINT CK_reservations_status
        CHECK (status IN ('PENDING','CHECKED_IN','COMPLETED','CANCELLED','NO_SHOW')),
    CONSTRAINT CK_res_cancel_type
        CHECK (cancel_type IN ('MEMBER','TRAINER','ADMIN','SYSTEM')),
    -- ★ 取消四欄同進同出：「取消了卻沒記錄誰取消的」在 DB 層不可能發生
    CONSTRAINT CK_res_cancel CHECK (
        (status =  'CANCELLED' AND cancel_type IS NOT NULL
                               AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
        OR
        (status <> 'CANCELLED' AND cancel_type IS NULL
                               AND cancelled_by IS NULL AND cancelled_at IS NULL)
    )
);
GO
-- ⚠️ 不要加 CHECK (start_at > SYSDATETIME()) 這類「不訂過去時段」的約束：
--    CHECK 在每次 UPDATE 都會重驗，等學員來報到時 start_at 已成過去 → 報到直接失敗。
--    這條規則放 Service 層。

CREATE TABLE session_deduction_logs (
    id               BIGINT         IDENTITY(1,1) NOT NULL,
    package_id       BIGINT         NOT NULL,
    reservation_id   BIGINT             NULL,                -- EXPIRE 無對應預約
    deduct_type      VARCHAR(20)    NOT NULL,
    sessions_delta   INT            NOT NULL,                -- 扣課 -1、退課 +1
    before_remaining INT            NOT NULL,
    after_remaining  INT            NOT NULL,                -- ★ 對帳關鍵
    operator_id      BIGINT         NOT NULL,                -- 教練／管理員／SYSTEM
    remark           NVARCHAR(200)      NULL,                -- 爭議註記
    created_at       DATETIME2      NOT NULL                 -- ★ 爭議時間軸依據
        CONSTRAINT DF_sdl_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_session_deduction_logs PRIMARY KEY (id),
    CONSTRAINT CK_deduct_type  CHECK (deduct_type IN ('DEDUCT','REVERT','EXPIRE','ADJUST')),
    CONSTRAINT CK_deduct_delta CHECK (sessions_delta <> 0),
    -- ★ 每筆異動自我驗證：剩餘堂數可由日誌重算，對不起來就是 bug 或人為竄改
    CONSTRAINT CK_deduct_math  CHECK (after_remaining = before_remaining + sessions_delta),
    -- ★ DEDUCT/REVERT 必有預約，否則 UX_deduction_once 的正確性沒有保障
    --   （SQL Server 的唯一索引只允許一個 NULL）
    CONSTRAINT CK_deduct_ref   CHECK (
        deduct_type NOT IN ('DEDUCT','REVERT') OR reservation_id IS NOT NULL
    )
);
GO

CREATE TABLE inventory_logs (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    product_id    BIGINT         NOT NULL,
    change_type   VARCHAR(20)    NOT NULL,
    quantity      BIGINT         NOT NULL,                   -- ★ 帶正負號
    balance_after BIGINT         NOT NULL,                   -- ★ 對帳的靈魂
    ref_order_id  BIGINT             NULL,                   -- 來源單號；進貨／盤點為 NULL
    operator_id   BIGINT         NOT NULL,
    description   NVARCHAR(100)      NULL,
    created_at    DATETIME2      NOT NULL
        CONSTRAINT DF_inv_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_inventory_logs PRIMARY KEY (id),
    CONSTRAINT CK_inv_type       CHECK (change_type IN ('INCOMING','OUTGOING','ADJUSTMENT')),
    -- ★ DB 直接守住正負號約定（ADJUSTMENT 可正可負，但不能為 0）
    CONSTRAINT CK_inv_sign CHECK (
        (change_type = 'INCOMING'   AND quantity > 0) OR
        (change_type = 'OUTGOING'   AND quantity < 0) OR
        (change_type = 'ADJUSTMENT' AND quantity <> 0)
    ),
    CONSTRAINT CK_inv_balance CHECK (balance_after >= 0)
);
GO

CREATE TABLE comments (
    id                 BIGINT         IDENTITY(1,1) NOT NULL,
    order_item_id      BIGINT         NOT NULL,              -- ★ 單一 FK 取代原本三個
    rating             INT            NOT NULL,
    content            NVARCHAR(200)      NULL,              -- 純評分不留言時為 NULL
    ai_sentiment_label NVARCHAR(20)       NULL,              -- GPT-4o-mini 產出
    ai_sentiment_score DECIMAL(4,3)       NULL,              -- 信心值
    analyzed_at        DATETIME2          NULL,              -- NULL = 待分析
    created_at         DATETIME2      NOT NULL
        CONSTRAINT DF_comments_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_comments            PRIMARY KEY (id),
    CONSTRAINT UQ_comments_order_item UNIQUE (order_item_id),  -- ★ 一筆明細只評一次
    CONSTRAINT CK_comments_rating     CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT CK_comments_score      CHECK (ai_sentiment_score BETWEEN 0 AND 1),
    CONSTRAINT CK_comments_label      CHECK (ai_sentiment_label IN ('POSITIVE','NEUTRAL','NEGATIVE'))
);
GO


/* =============================================================================
 * 4. 外鍵
 *
 * 刪除策略：
 *   orders → order_items          CASCADE   明細不能脫離訂單存在
 *   products → order_items        NO ACTION 保護歷史訂單（商品用 status='INACTIVE' 軟下架）
 *   ⚠️ users → orders             NO ACTION 【勿改 CASCADE】
 *      改了會讓 users → order_items 出現兩條串聯路徑，建表直接報
 *      "may cause cycles or multiple cascade paths"
 *   其餘全部 NO ACTION：有場次的教室／課種刪不掉；被訂過的場次刪不掉
 * ========================================================================== */

ALTER TABLE trainer_details ADD CONSTRAINT FK_td_user     FOREIGN KEY (user_id)   REFERENCES users(id);
ALTER TABLE products        ADD CONSTRAINT FK_prod_course FOREIGN KEY (course_id) REFERENCES courses(id);

ALTER TABLE trainer_schedules ADD CONSTRAINT FK_sched_trainer FOREIGN KEY (trainer_id) REFERENCES users(id);
ALTER TABLE trainer_schedules ADD CONSTRAINT FK_sched_course  FOREIGN KEY (course_id)  REFERENCES courses(id);
ALTER TABLE trainer_schedules ADD CONSTRAINT FK_sched_room    FOREIGN KEY (room_id)    REFERENCES rooms(id);

ALTER TABLE orders ADD CONSTRAINT FK_orders_user FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE order_items ADD CONSTRAINT FK_oi_order   FOREIGN KEY (order_id)   REFERENCES orders(id) ON DELETE CASCADE;
ALTER TABLE order_items ADD CONSTRAINT FK_oi_product FOREIGN KEY (product_id) REFERENCES products(id);

ALTER TABLE member_packages ADD CONSTRAINT FK_pkg_user   FOREIGN KEY (user_id)       REFERENCES users(id);
ALTER TABLE member_packages ADD CONSTRAINT FK_pkg_oi     FOREIGN KEY (order_item_id) REFERENCES order_items(id);
ALTER TABLE member_packages ADD CONSTRAINT FK_pkg_course FOREIGN KEY (course_id)     REFERENCES courses(id);

ALTER TABLE reservations ADD CONSTRAINT FK_res_schedule  FOREIGN KEY (schedule_id)  REFERENCES trainer_schedules(id);
ALTER TABLE reservations ADD CONSTRAINT FK_res_member    FOREIGN KEY (member_id)    REFERENCES users(id);
ALTER TABLE reservations ADD CONSTRAINT FK_res_package   FOREIGN KEY (package_id)   REFERENCES member_packages(id);
ALTER TABLE reservations ADD CONSTRAINT FK_res_cancelled FOREIGN KEY (cancelled_by) REFERENCES users(id);

ALTER TABLE session_deduction_logs ADD CONSTRAINT FK_sdl_package  FOREIGN KEY (package_id)     REFERENCES member_packages(id);
ALTER TABLE session_deduction_logs ADD CONSTRAINT FK_sdl_res      FOREIGN KEY (reservation_id) REFERENCES reservations(id);
ALTER TABLE session_deduction_logs ADD CONSTRAINT FK_sdl_operator FOREIGN KEY (operator_id)    REFERENCES users(id);

ALTER TABLE cart_items ADD CONSTRAINT FK_cart_user    FOREIGN KEY (user_id)    REFERENCES users(id);
ALTER TABLE cart_items ADD CONSTRAINT FK_cart_product FOREIGN KEY (product_id) REFERENCES products(id);

ALTER TABLE inventory_logs ADD CONSTRAINT FK_inv_product  FOREIGN KEY (product_id)   REFERENCES products(id);
ALTER TABLE inventory_logs ADD CONSTRAINT FK_inv_order    FOREIGN KEY (ref_order_id) REFERENCES orders(id);
ALTER TABLE inventory_logs ADD CONSTRAINT FK_inv_operator FOREIGN KEY (operator_id)  REFERENCES users(id);

ALTER TABLE chat_messages ADD CONSTRAINT FK_chat_sender   FOREIGN KEY (sender_id)   REFERENCES users(id);
ALTER TABLE chat_messages ADD CONSTRAINT FK_chat_receiver FOREIGN KEY (receiver_id) REFERENCES users(id);

ALTER TABLE comments ADD CONSTRAINT FK_comments_oi FOREIGN KEY (order_item_id) REFERENCES order_items(id);

ALTER TABLE inbody_records  ADD CONSTRAINT FK_inbody_user FOREIGN KEY (user_id)          REFERENCES users(id);
ALTER TABLE ai_health_plans ADD CONSTRAINT FK_plan_user   FOREIGN KEY (user_id)          REFERENCES users(id);
ALTER TABLE ai_health_plans ADD CONSTRAINT FK_plan_inbody FOREIGN KEY (inbody_record_id) REFERENCES inbody_records(id);
GO


/* =============================================================================
 * 5. 索引
 *
 * ⚠️ 篩選式唯一索引（WHERE ...）解決 SQL Server 兩個陷阱：
 *   1. UNIQUE 只允許一個 NULL（與 MySQL/PostgreSQL 不同）
 *      → nullable 欄位加 UNIQUE 時必須用 WHERE xxx IS NOT NULL
 *   2. 唯一索引不認狀態欄位
 *      → 軟取消的表必須加 WHERE status <> 'CANCELLED'，否則取消過的資料
 *        會永久占用該組合（原 UQ_trainer_reservation 的致命傷）
 * ========================================================================== */

-- users
CREATE UNIQUE INDEX UX_users_email ON users(email);
CREATE INDEX IX_users_role ON users(role, status);
--   oauth_sub 為 nullable（密碼註冊的使用者全是 NULL）→ 必須篩選式
CREATE UNIQUE INDEX UX_users_oauth ON users(oauth_provider, oauth_sub)
    WHERE oauth_sub IS NOT NULL;
GO

-- courses / rooms
CREATE UNIQUE INDEX UX_courses_name ON courses(name);
CREATE INDEX IX_courses_status ON courses(status);
CREATE UNIQUE INDEX UX_rooms_name ON rooms(name);
CREATE INDEX IX_rooms_status ON rooms(status);
GO

-- products
CREATE INDEX IX_products_type   ON products(type, status);
CREATE INDEX IX_products_course ON products(course_id);
GO

-- trainer_schedules
--   UX_schedule_slot 只擋「相同起始時間」＝防重複提交（連點兩下建立按鈕）
--   ⚠️ 擋不住部分重疊（10:00–11:30 vs 11:00–12:00）→ Service 層檢查
CREATE UNIQUE INDEX UX_schedule_slot ON trainer_schedules(trainer_id, start_at);
--   「瑜珈近期場次、還剩幾位」；INCLUDE 避免回表
CREATE INDEX IX_schedule_browse ON trainer_schedules(course_id, start_at)
    INCLUDE (trainer_id, room_id, capacity, booked_count);
--   Service 的教練重疊檢查
CREATE INDEX IX_schedule_overlap ON trainer_schedules(trainer_id, start_at, end_at);
--   Service 的教室重疊檢查
CREATE INDEX IX_schedule_room ON trainer_schedules(room_id, start_at, end_at)
    WHERE room_id IS NOT NULL;
--   統計看板按歸屬日 GROUP BY；NO_SHOW/COMPLETED 排程從場次端驅動
CREATE INDEX IX_schedule_date ON trainer_schedules(schedule_date, trainer_id);
GO

-- reservations
--   ★ 同一學員不重複報名同一場次
CREATE UNIQUE INDEX UX_reservations_member_schedule
    ON reservations(schedule_id, member_id) WHERE status <> 'CANCELLED';
--   ★ 金鑰只需在「當下可核銷」的預約之間唯一 → 篩 PENDING，舊金鑰碰撞無害
--     全域唯一的話：25,000 場次/年 × 10 年 ≈ 25 萬組，生日碰撞機率約 2.8%
CREATE UNIQUE INDEX UX_reservations_checkin_key
    ON reservations(check_in_key) WHERE status = 'PENDING' AND check_in_key IS NOT NULL;
CREATE INDEX IX_reservations_member ON reservations(member_id, status);
CREATE INDEX IX_reservations_sched  ON reservations(schedule_id, status);
GO

-- member_packages
CREATE INDEX IX_packages_user ON member_packages(user_id, status);
GO

-- session_deduction_logs
--   ★ 一筆預約只扣一次課（退課另列新紀錄，不 UPDATE 舊列）
--     reservation_id 為 nullable，但 CK_deduct_ref 保證 DEDUCT 子集無 NULL
CREATE UNIQUE INDEX UX_deduction_once
    ON session_deduction_logs(reservation_id) WHERE deduct_type = 'DEDUCT';
CREATE INDEX IX_deduction_package ON session_deduction_logs(package_id, created_at);
GO

-- cart_items
--   ★ 同商品不得重複列；加購改為 UPDATE quantity
CREATE UNIQUE INDEX UX_cart_user_product ON cart_items(user_id, product_id);
GO

-- orders
--   ★ 綠界冪等第一防線。trade_no 為 nullable（未付款訂單全是 NULL）→ 必須篩選式
CREATE UNIQUE INDEX UX_orders_trade_no ON orders(trade_no) WHERE trade_no IS NOT NULL;
CREATE INDEX IX_orders_user ON orders(user_id, created_at DESC);
GO

-- order_items / inventory_logs
CREATE INDEX IX_order_items_order ON order_items(order_id);
CREATE INDEX IX_inv_product ON inventory_logs(product_id, created_at DESC);
GO

-- chat_messages
--   ⚠️ 原 IX_chat_created_at ON (created_at) 已移除：實際查詢的 WHERE 條件不在
--      created_at 上，最佳化程式不會選用 → 建了等於沒建。
--      加 room_id 後查詢變成 WHERE room_id = ?，索引才生效。
CREATE INDEX IX_chat_room_time ON chat_messages(room_id, created_at DESC);
--   未讀紅點；篩選式 → 索引體積極小
CREATE INDEX IX_chat_unread ON chat_messages(receiver_id, is_read) WHERE is_read = 0;
GO

-- comments
--   非同步 AI 分析佇列的掃描條件
CREATE INDEX IX_comments_pending ON comments(created_at) WHERE analyzed_at IS NULL;
GO

-- inbody / ai_health_plans
CREATE INDEX IX_inbody_user_date ON inbody_records(user_id, record_date DESC);
CREATE INDEX IX_plans_user ON ai_health_plans(user_id, created_at DESC);
GO


/* ═══════════════════════════════════════════════════════════════════════
 * PART 2：優惠券（2 張表 + orders 加三欄）
 * ═══════════════════════════════════════════════════════════════════════ */

CREATE TABLE coupons (
    id              BIGINT         IDENTITY(1,1) NOT NULL,
    code            VARCHAR(30)    NOT NULL,                 -- 兌換碼，如 WELCOME100（共用碼）
    name            NVARCHAR(50)   NOT NULL,                 -- 券名稱（後台顯示）
    discount_type   VARCHAR(20)    NOT NULL,                 -- FIXED（滿減）/ PERCENT（打折）
    discount_value  DECIMAL(10,2)  NOT NULL,                 -- FIXED：折抵元；PERCENT：折扣率(如 0.90=打9折)
    min_spend       DECIMAL(10,2)  NOT NULL                  -- 最低消費門檻（0=無門檻）
        CONSTRAINT DF_coupons_minspend DEFAULT (0),
    max_discount    DECIMAL(10,2)      NULL,                 -- 折抵上限（PERCENT 用，避免折太多；FIXED 為 NULL）
    total_quantity  INT                NULL,                 -- 發行總量（NULL=無限量）
    used_count      INT            NOT NULL                  -- 已使用數（冗餘計數，悲觀鎖 + CHECK 守）
        CONSTRAINT DF_coupons_used DEFAULT (0),
    start_at        DATETIME2      NOT NULL,                 -- 生效時間
    end_at          DATETIME2      NOT NULL,                 -- 失效時間
    status          VARCHAR(20)    NOT NULL                  -- ACTIVE / INACTIVE
        CONSTRAINT DF_coupons_status DEFAULT ('ACTIVE'),
    created_at      DATETIME2      NOT NULL
        CONSTRAINT DF_coupons_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),
    updated_at      DATETIME2      NOT NULL
        CONSTRAINT DF_coupons_updated DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_coupons          PRIMARY KEY (id),
    CONSTRAINT CK_coupons_type     CHECK (discount_type IN ('FIXED','PERCENT')),
    CONSTRAINT CK_coupons_status   CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT CK_coupons_value    CHECK (discount_value > 0),
    CONSTRAINT CK_coupons_minspend CHECK (min_spend >= 0),
    -- 折扣型別完整性：PERCENT 的值必須是 0~1 之間的比率；FIXED 無此限制
    CONSTRAINT CK_coupons_shape    CHECK (
        (discount_type = 'PERCENT' AND discount_value > 0 AND discount_value < 1)
        OR
        (discount_type = 'FIXED'   AND max_discount IS NULL)
    ),
    -- 時間 / 數量守門
    CONSTRAINT CK_coupons_time     CHECK (end_at > start_at),
    CONSTRAINT CK_coupons_quantity CHECK (total_quantity IS NULL OR total_quantity >= 0),
    -- ★ 超發兜底：已使用數不可超過發行總量（無限量則跳過）
    CONSTRAINT CK_coupons_used     CHECK (total_quantity IS NULL OR used_count <= total_quantity)
);

-- 兌換碼唯一（共用碼，全系統唯一）
CREATE UNIQUE INDEX UX_coupons_code ON coupons(code);

-- ---------------------------------------------------------------------
-- 2. coupon_usages — 優惠券使用紀錄
-- ---------------------------------------------------------------------
CREATE TABLE coupon_usages (
    id              BIGINT         IDENTITY(1,1) NOT NULL,
    coupon_id       BIGINT         NOT NULL,                 -- 用了哪張券
    user_id         BIGINT         NOT NULL,                 -- 誰用的
    order_id        BIGINT         NOT NULL,                 -- 用在哪張訂單
    discount_amount DECIMAL(10,2)  NOT NULL,                 -- 實際折抵金額（快照，券日後改不影響）
    created_at      DATETIME2      NOT NULL
        CONSTRAINT DF_couponusage_created DEFAULT (CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)),

    CONSTRAINT PK_coupon_usages       PRIMARY KEY (id),
    CONSTRAINT CK_couponusage_amount  CHECK (discount_amount >= 0),
    -- ★ 冪等：同一張訂單只能用一次券（防重複折抵）
    CONSTRAINT UQ_couponusage_order   UNIQUE (order_id)
);

-- 反查：某張券被用了幾次（對帳用，應等於 coupons.used_count）
CREATE INDEX IX_couponusage_coupon ON coupon_usages(coupon_id);
-- 某會員用過哪些券（可用於「一人限用一次」的檢查）
CREATE INDEX IX_couponusage_user ON coupon_usages(user_id, coupon_id);

-- ---------------------------------------------------------------------
-- 3. orders 加三欄：原價 / 折抵 / 應付
--    total_amount 維持「應付金額」（折抵後），與綠界收款一致；
--    另加 original_amount（折抵前）與 discount_amount（折抵）供帳目追溯。
-- ---------------------------------------------------------------------
ALTER TABLE orders ADD
    coupon_id        BIGINT             NULL,                -- 用了哪張券（NULL=沒用券）
    original_amount  DECIMAL(10,2)      NULL,                -- 折抵前原價（NULL=舊資料/沒用券）
    discount_amount  DECIMAL(10,2)  NOT NULL                 -- 折抵金額（沒用券=0）
        CONSTRAINT DF_orders_discount DEFAULT (0);

-- ---------------------------------------------------------------------
-- 4. 外鍵
-- ---------------------------------------------------------------------
ALTER TABLE coupon_usages ADD CONSTRAINT FK_couponusage_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id);
ALTER TABLE coupon_usages ADD CONSTRAINT FK_couponusage_user   FOREIGN KEY (user_id)   REFERENCES users(id);
ALTER TABLE coupon_usages ADD CONSTRAINT FK_couponusage_order  FOREIGN KEY (order_id)  REFERENCES orders(id);
ALTER TABLE orders        ADD CONSTRAINT FK_orders_coupon      FOREIGN KEY (coupon_id) REFERENCES coupons(id);


/* ═══════════════════════════════════════════════════════════════════════
 * PART 3：系統帳號 seed（SYSTEM，id=1）
 * ═══════════════════════════════════════════════════════════════════════ */

SET IDENTITY_INSERT users ON;

INSERT INTO users (id, first_name, last_name, email, role, pwd_hash, status, created_at, updated_at)
VALUES (
    1,
    N'System',
    N'Account',
    'system@gym.internal',
    'ADMIN',
    '!SYSTEM_ACCOUNT_NO_LOGIN!',    -- 非 BCrypt 格式 → 永遠比對不過
    'SUSPENDED',                    -- 第二道防線
    CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2),
    CAST(SYSDATETIMEOFFSET() AT TIME ZONE 'Taipei Standard Time' AS DATETIME2)
);

SET IDENTITY_INSERT users OFF;
GO
