# -*- coding: utf-8 -*-
"""一次性：Metabase 初始設定 + 接 SQL Server + 建圖卡/儀表板 + 開 public link。

重跑方式（repo 根目錄）：set -a && . ./.env && set +a && python bi/metabase_setup.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

# 可用環境變數覆蓋，供全容器模式的 metabase-init 一次性容器使用（見根目錄 docker-compose.yml）：
# MB_BASE_URL：腳本呼叫 Metabase API 的位置（容器網路內是 http://metabase:3000）
# MB_PUBLIC_BASE_URL：寫給瀏覽器用的公開網址基底（永遠是宿主視角的 http://localhost:3001）
# MB_DB_HOST／MB_DB_PORT：Metabase 連 SQL Server 的位置（開發模式=宿主機、全容器=sqlserver）
# MB_URL_OUT：public 儀表板網址輸出檔——前端「營運分析」頁執行期讀 /mbconfig/url.txt，
#             預設寫進 frontend/public/mbconfig/（gitignored，各機器自己的）
BASE = os.environ.get("MB_BASE_URL", "http://localhost:3001")
PUBLIC_BASE = os.environ.get("MB_PUBLIC_BASE_URL", "http://localhost:3001")
DB_HOST = os.environ.get("MB_DB_HOST", "host.docker.internal")
DB_PORT = int(os.environ.get("MB_DB_PORT", "1433"))
URL_OUT = os.environ.get("MB_URL_OUT", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "frontend", "public", "mbconfig", "url.txt"))
ADMIN_EMAIL = "admin@local.test"
ADMIN_PW = "Gym@Admin12345"
DB_PASSWORD = os.environ["DB_PASSWORD"]


def req(method, path, token=None, body=None):
    r = urllib.request.Request(BASE + path, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("X-Metabase-Session", token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(r, data, timeout=120) as resp:
            t = resp.read().decode()
            return resp.status, (json.loads(t) if t else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


# 1. 等 Metabase 起來（首次啟動要初始化，可能要一兩分鐘）
for i in range(90):
    s, _ = req("GET", "/api/health")
    if s == 200:
        print("metabase healthy")
        break
    time.sleep(5)
else:
    sys.exit("metabase not healthy after 450s")

# 2. 初始設定（只建管理員；資料庫另外加，失敗才好重試）
s, props = req("GET", "/api/session/properties")
setup_token = props.get("setup-token") if isinstance(props, dict) else None
if setup_token:
    s, res = req("POST", "/api/setup", body={
        "token": setup_token,
        "user": {"first_name": "Gym", "last_name": "Admin",
                 "email": ADMIN_EMAIL, "password": ADMIN_PW},
        "prefs": {"site_name": "威力健身房", "site_locale": "zh-TW",
                  "allow_tracking": False},
    })
    if s != 200:
        sys.exit(f"setup failed {s}: {res}")
    session = res["id"]
    print("setup done")
else:
    s, res = req("POST", "/api/session",
                 body={"username": ADMIN_EMAIL, "password": ADMIN_PW})
    if s != 200:
        sys.exit(f"login failed {s}: {res}")
    session = res["id"]
    print("already set up, logged in")

# 3. 接 SQL Server（自簽憑證要 trustServerCertificate）
s, dbs = req("GET", "/api/database", token=session)
existing = [d for d in (dbs["data"] if isinstance(dbs, dict) else dbs)
            if d.get("name") == "gym"]
if existing:
    db_id = existing[0]["id"]
    print(f"database exists id={db_id}")
else:
    details_variants = [
        {"host": DB_HOST, "port": DB_PORT, "db": "ispan-proj-gymdb",
         "user": "sa", "password": DB_PASSWORD, "ssl": False,
         "additional-options": "trustServerCertificate=true"},
        {"host": DB_HOST, "port": DB_PORT, "db": "ispan-proj-gymdb",
         "user": "sa", "password": DB_PASSWORD, "ssl": False,
         "additional-options": "encrypt=false"},
    ]
    db_id = None
    for details in details_variants:
        s, res = req("POST", "/api/database", token=session, body={
            "engine": "sqlserver", "name": "gym", "details": details})
        if s == 200:
            db_id = res["id"]
            print(f"database connected id={db_id} via {details['additional-options']}")
            break
        print(f"database variant failed {s}: {str(res)[:300]}")
    if db_id is None:
        sys.exit("all database variants failed")

# 3.5 等 schema 同步完成，拿日期欄位 id——field filter 要綁到具體欄位
WANT_FIELDS = {("orders", "paid_at"): None, ("orders", "created_at"): None,
               ("coach_slot", "start_at"): None}
for i in range(60):
    s, meta = req("GET", f"/api/database/{db_id}/metadata", token=session)
    if s == 200:
        for t in meta.get("tables", []):
            for f in t.get("fields", []):
                key = (t["name"], f["name"])
                if key in WANT_FIELDS:
                    WANT_FIELDS[key] = f["id"]
        if all(v is not None for v in WANT_FIELDS.values()):
            break
    time.sleep(5)
else:
    sys.exit("schema 同步逾時，拿不到日期欄位 id")
F_PAID = WANT_FIELDS[("orders", "paid_at")]
F_CREATED = WANT_FIELDS[("orders", "created_at")]
F_SLOT = WANT_FIELDS[("coach_slot", "start_at")]
print(f"field ids: paid_at={F_PAID} created_at={F_CREATED} slot_start={F_SLOT}")

# 4. 建圖卡。設計要點：
# - 儀表板刻意只放「營運統計」頁沒有的探索維度——兩頁職責不同：對帳走快照、探索走 BI；
#   日期趨勢類另存成卡（不上儀表板），當「已修正時區口徑（DATEADD +8）」的示範。
# - 五張上板卡都掛 field filter（SQL 裡的 [[AND {{tag}}]]），由儀表板頂端的
#   日期選擇器統一驅動；未設篩選＝開站以來。
# - filter 比的是 DB 存的 UTC 牆鐘時間，日界有 8 小時偏移（Demo 可接受，README 有註記）。
# - field filter 綁的表在 SQL 中不能取別名（Metabase 會以原表名注入條件）。
DESC_PAID = "日期依付款時間篩選；未設篩選＝開站以來（DB 存 UTC 牆鐘，日界有 8 小時偏移）"
DESC_CREATED = "日期依下單時間篩選；未設篩選＝開站以來（DB 存 UTC 牆鐘，日界有 8 小時偏移）"
DESC_SLOT = "日期依時段開始時間篩選；未設篩選＝開站以來（DB 存 UTC 牆鐘，日界有 8 小時偏移）"

# (名稱, 圖型, tag 名, tag 顯示名, 欄位 id, 描述, SQL)；tag 為 None 的不掛 filter
CARDS = [
    ("各商品分類營收", "bar", "paid_date", "付款日期", "F_PAID", DESC_PAID, """
SELECT pc.name AS [分類], SUM(oi.quantity * oi.price_at_buy) AS [營收]
FROM order_item oi
JOIN orders ON orders.order_id = oi.order_id
JOIN product p ON p.product_id = oi.product_id
JOIN product_category pc ON pc.product_category_id = p.category_id
WHERE orders.status IN ('PAID','SHIPPED','COMPLETED')
[[AND {{paid_date}}]]
GROUP BY pc.name
ORDER BY [營收] DESC"""),
    ("付款方式分布", "pie", "order_date", "下單日期", "F_CREATED", DESC_CREATED, """
SELECT ISNULL(payment_method, N'未付款') AS [付款方式], COUNT(*) AS [筆數]
FROM orders
WHERE 1=1 [[AND {{order_date}}]]
GROUP BY payment_method"""),
    ("營收組成", "pie", "paid_date", "付款日期", "F_PAID", DESC_PAID, """
SELECT t.[類型], SUM(t.[金額]) AS [營收] FROM (
    SELECT CASE WHEN oi.product_id IS NOT NULL THEN N'商品' ELSE N'私人課程' END AS [類型],
           oi.quantity * oi.price_at_buy AS [金額]
    FROM order_item oi JOIN orders ON orders.order_id = oi.order_id
    WHERE orders.status IN ('PAID','SHIPPED','COMPLETED') [[AND {{paid_date}}]]
    UNION ALL
    SELECT N'會籍訂閱', total_amount
    FROM orders
    WHERE subscription_plan_id IS NOT NULL AND status IN ('PAID','SHIPPED','COMPLETED') [[AND {{paid_date}}]]
) t GROUP BY t.[類型]"""),
    ("各教練預約數", "row", "slot_date", "時段日期", "F_SLOT", DESC_SLOT, """
SELECT tr.last_name + tr.first_name AS [教練], COUNT(*) AS [預約數]
FROM coach_booking cb
JOIN coach_slot ON coach_slot.id = cb.coach_slot_id
JOIN trainer tr ON tr.trainer_id = coach_slot.trainer_id
WHERE 1=1 [[AND {{slot_date}}]]
GROUP BY tr.last_name, tr.first_name
ORDER BY [預約數] DESC"""),
    ("商品銷售排行", "row", "paid_date", "付款日期", "F_PAID", DESC_PAID, """
SELECT TOP 10 oi.product_name_at_buy AS [商品],
       SUM(oi.quantity * oi.price_at_buy) AS [金額]
FROM order_item oi JOIN orders ON orders.order_id = oi.order_id
WHERE orders.status IN ('PAID','SHIPPED','COMPLETED') AND oi.product_id IS NOT NULL
[[AND {{paid_date}}]]
GROUP BY oi.product_name_at_buy
ORDER BY [金額] DESC"""),
]
# 只存卡、不上儀表板：日期趨勢（DATEADD +8 修正示範，與營運統計頁重疊故不展示）
EXTRA_CARDS = [
    ("每日營收趨勢", "line", None, None, None, None, """
SELECT CAST(DATEADD(HOUR, 8, paid_at) AS DATE) AS [日期], SUM(total_amount) AS [營收]
FROM orders
WHERE paid_at IS NOT NULL AND status IN ('PAID','SHIPPED','COMPLETED')
GROUP BY CAST(DATEADD(HOUR, 8, paid_at) AS DATE)
ORDER BY [日期]"""),
    ("每日新會員", "bar", None, None, None, None, """
SELECT CAST(DATEADD(HOUR, 8, created_at) AS DATE) AS [日期], COUNT(*) AS [新會員]
FROM member WHERE deleted_at IS NULL
GROUP BY CAST(DATEADD(HOUR, 8, created_at) AS DATE)
ORDER BY [日期]"""),
]
FIELD_BY_KEY = {"F_PAID": F_PAID, "F_CREATED": F_CREATED, "F_SLOT": F_SLOT}

card_ids = []
tag_by_card = {}
for name, display, tname, tdisp, fkey, desc, sql in CARDS + EXTRA_CARDS:
    native = {"query": sql.strip()}
    if tname:
        native["template-tags"] = {tname: {
            "id": f"tt-{tname}-{len(card_ids)}", "name": tname, "display-name": tdisp,
            "type": "dimension", "dimension": ["field", FIELD_BY_KEY[fkey], None],
            "widget-type": "date/all-options"}}
    s, res = req("POST", "/api/card", token=session, body={
        "name": name, "display": display, "visualization_settings": {},
        "collection_id": None, "description": desc,
        "dataset_query": {"type": "native", "database": db_id, "native": native}})
    if s != 200:
        sys.exit(f"card '{name}' failed {s}: {str(res)[:300]}")
    card_ids.append(res["id"])
    if tname:
        tag_by_card[res["id"]] = tname
    print(f"card '{name}' id={res['id']}")

# 5. 儀表板：排版＋掛日期參數（date/all-options），逐卡對映到各自的 template tag
s, res = req("POST", "/api/dashboard", token=session, body={"name": "營運總覽"})
if s != 200:
    sys.exit(f"dashboard failed {s}: {res}")
dash_id = res["id"]
PARAM_ID = "gymdate1"
positions = [
    (card_ids[0], 0, 0, 14, 8),
    (card_ids[1], 0, 14, 10, 8),
    (card_ids[2], 8, 0, 10, 8),
    (card_ids[3], 8, 10, 14, 8),
    (card_ids[4], 16, 0, 24, 8),
]
dashcards = []
for i, (cid, row, col, sx, sy) in enumerate(positions):
    entry = {"id": -(i + 1), "card_id": cid, "row": row, "col": col,
             "size_x": sx, "size_y": sy, "parameter_mappings": []}
    if cid in tag_by_card:
        entry["parameter_mappings"] = [{
            "parameter_id": PARAM_ID, "card_id": cid,
            "target": ["dimension", ["template-tag", tag_by_card[cid]]]}]
    dashcards.append(entry)
s, res = req("PUT", f"/api/dashboard/{dash_id}", token=session, body={
    "parameters": [{"id": PARAM_ID, "name": "日期", "slug": "date",
                    "type": "date/all-options", "sectionId": "date"}],
    "dashcards": dashcards})
if s != 200:
    sys.exit(f"PUT dashcards failed {s}: {str(res)[:300]}")
print(f"dashboard id={dash_id} laid out with date filter")

# 6. 開 public sharing + 產生公開連結
s, res = req("PUT", "/api/setting/enable-public-sharing", token=session,
             body={"value": True})
print(f"enable-public-sharing: {s}")
s, res = req("POST", f"/api/dashboard/{dash_id}/public_link", token=session, body={})
if s != 200:
    sys.exit(f"public link failed {s}: {res}")
uuid = res["uuid"]
public_url = f"{PUBLIC_BASE}/public/dashboard/{uuid}"
print("PUBLIC_URL=" + public_url)
print("DASH_ID=" + str(dash_id))

# 寫給前端「營運分析」頁執行期讀取——public link 是各機器自己的，不能寫死在程式碼裡
try:
    os.makedirs(os.path.dirname(URL_OUT), exist_ok=True)
    with open(URL_OUT, "w", encoding="utf-8") as f:
        f.write(public_url + "\n")
    print("URL written to " + URL_OUT)
except OSError as e:
    print(f"WARN: 寫入 {URL_OUT} 失敗（{e}），請手動把 PUBLIC_URL 放進 frontend/public/mbconfig/url.txt")
