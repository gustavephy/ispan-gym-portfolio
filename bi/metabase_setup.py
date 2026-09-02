# -*- coding: utf-8 -*-
"""一次性：Metabase 初始設定 + 接 SQL Server + 建圖卡/儀表板 + 開 public link。"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:3001"
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
        {"host": "host.docker.internal", "port": 1433, "db": "ispan-proj-gymdb",
         "user": "sa", "password": DB_PASSWORD, "ssl": False,
         "additional-options": "trustServerCertificate=true"},
        {"host": "host.docker.internal", "port": 1433, "db": "ispan-proj-gymdb",
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

# 4. 建圖卡（原始時間是 UTC 牆鐘，日期一律 DATEADD +8 轉台北——與後端修正同一口徑）。
# 儀表板刻意只放「營運統計」頁沒有的探索維度——兩頁職責不同：對帳走快照、探索走 BI；
# 日期趨勢類另存成卡（不上儀表板），當「已修正時區口徑」的示範。
CARDS = [
    ("各商品分類營收", "bar", """
SELECT pc.name AS [分類], SUM(oi.quantity * oi.price_at_buy) AS [營收]
FROM order_item oi
JOIN orders o ON o.order_id = oi.order_id
JOIN product p ON p.product_id = oi.product_id
JOIN product_category pc ON pc.product_category_id = p.category_id
WHERE o.status IN ('PAID','SHIPPED','COMPLETED')
GROUP BY pc.name
ORDER BY [營收] DESC"""),
    ("付款方式分布", "pie", """
SELECT ISNULL(payment_method, N'未付款') AS [付款方式], COUNT(*) AS [筆數]
FROM orders GROUP BY payment_method"""),
    ("營收組成", "pie", """
SELECT t.[類型], SUM(t.[金額]) AS [營收] FROM (
    SELECT CASE WHEN oi.product_id IS NOT NULL THEN N'商品' ELSE N'私人課程' END AS [類型],
           oi.quantity * oi.price_at_buy AS [金額]
    FROM order_item oi JOIN orders o ON o.order_id = oi.order_id
    WHERE o.status IN ('PAID','SHIPPED','COMPLETED')
    UNION ALL
    SELECT N'會籍訂閱', o.total_amount
    FROM orders o
    WHERE o.subscription_plan_id IS NOT NULL AND o.status IN ('PAID','SHIPPED','COMPLETED')
) t GROUP BY t.[類型]"""),
    ("各教練預約數", "row", """
SELECT tr.last_name + tr.first_name AS [教練], COUNT(*) AS [預約數]
FROM coach_booking cb
JOIN coach_slot cs ON cs.id = cb.coach_slot_id
JOIN trainer tr ON tr.trainer_id = cs.trainer_id
GROUP BY tr.last_name, tr.first_name
ORDER BY [預約數] DESC"""),
    ("商品銷售排行", "row", """
SELECT TOP 10 oi.product_name_at_buy AS [商品],
       SUM(oi.quantity * oi.price_at_buy) AS [金額]
FROM order_item oi JOIN orders o ON o.order_id = oi.order_id
WHERE o.status IN ('PAID','SHIPPED','COMPLETED') AND oi.product_id IS NOT NULL
GROUP BY oi.product_name_at_buy
ORDER BY [金額] DESC"""),
]
# 只存卡、不上儀表板：日期趨勢（DATEADD +8 修正示範，與營運統計頁重疊故不展示）
EXTRA_CARDS = [
    ("每日營收趨勢", "line", """
SELECT CAST(DATEADD(HOUR, 8, paid_at) AS DATE) AS [日期], SUM(total_amount) AS [營收]
FROM orders
WHERE paid_at IS NOT NULL AND status IN ('PAID','SHIPPED','COMPLETED')
GROUP BY CAST(DATEADD(HOUR, 8, paid_at) AS DATE)
ORDER BY [日期]"""),
    ("每日新會員", "bar", """
SELECT CAST(DATEADD(HOUR, 8, created_at) AS DATE) AS [日期], COUNT(*) AS [新會員]
FROM member WHERE deleted_at IS NULL
GROUP BY CAST(DATEADD(HOUR, 8, created_at) AS DATE)
ORDER BY [日期]"""),
]
card_ids = []
for name, display, sql in CARDS + EXTRA_CARDS:
    s, res = req("POST", "/api/card", token=session, body={
        "name": name, "display": display, "visualization_settings": {},
        "collection_id": None,
        "dataset_query": {"type": "native", "database": db_id,
                          "native": {"query": sql.strip()}}})
    if s != 200:
        sys.exit(f"card '{name}' failed {s}: {str(res)[:300]}")
    card_ids.append(res["id"])
    print(f"card '{name}' id={res['id']}")

# 5. 儀表板 + 排版（新版 API 用 PUT dashcards，失敗退回舊版 POST cards）
s, res = req("POST", "/api/dashboard", token=session, body={"name": "營運總覽"})
if s != 200:
    sys.exit(f"dashboard failed {s}: {res}")
dash_id = res["id"]
layout = [
    {"id": -1, "card_id": card_ids[0], "row": 0, "col": 0, "size_x": 14, "size_y": 8},
    {"id": -2, "card_id": card_ids[1], "row": 0, "col": 14, "size_x": 10, "size_y": 8},
    {"id": -3, "card_id": card_ids[2], "row": 8, "col": 0, "size_x": 10, "size_y": 8},
    {"id": -4, "card_id": card_ids[3], "row": 8, "col": 10, "size_x": 14, "size_y": 8},
    {"id": -5, "card_id": card_ids[4], "row": 16, "col": 0, "size_x": 24, "size_y": 8},
]
s, res = req("PUT", f"/api/dashboard/{dash_id}", token=session,
             body={"dashcards": layout})
if s == 200:
    print(f"dashboard id={dash_id} laid out (PUT)")
else:
    print(f"PUT dashcards failed {s}: {str(res)[:200]}; trying legacy API")
    for c in layout:
        s2, res2 = req("POST", f"/api/dashboard/{dash_id}/cards", token=session,
                       body={"cardId": c["card_id"], "row": c["row"], "col": c["col"],
                             "size_x": c["size_x"], "size_y": c["size_y"]})
        print(f"legacy add card {c['card_id']}: {s2}")

# 6. 開 public sharing + 產生公開連結
s, res = req("PUT", "/api/setting/enable-public-sharing", token=session,
             body={"value": True})
print(f"enable-public-sharing: {s}")
s, res = req("POST", f"/api/dashboard/{dash_id}/public_link", token=session, body={})
if s != 200:
    sys.exit(f"public link failed {s}: {res}")
uuid = res["uuid"]
print("PUBLIC_URL=" + f"{BASE}/public/dashboard/{uuid}")
print("DASH_ID=" + str(dash_id))
