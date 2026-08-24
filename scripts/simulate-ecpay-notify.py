#!/usr/bin/env python3
"""
模擬綠界的付款結果通知（server-to-server callback），直接打進本機後端。

為什麼需要：本機開發時 ReturnURL 指向 localhost，綠界的伺服器連不進來
（見 EcpayService 的類別註解），所以在測試頁按下「交易成功」後，通知不會
送達、訂單一直停在 UNPAID。這支腳本用與後端相同的 CheckMacValue 演算法
自行簽章並送出通知，讓訂單推進到 PAID 並觸發履約（發放課程堂數、啟用訂閱），
不必架 ngrok。

用法：
    python scripts/simulate-ecpay-notify.py                    # 自動挑最新一筆未付款訂單
    python scripts/simulate-ecpay-notify.py GYM20260818150012833
    python scripts/simulate-ecpay-notify.py --fail             # 模擬付款失敗（訂單轉 FAILED）

金鑰預設讀 application.properties 裡 ${ENV:預設值} 的預設值（綠界測試環境的公開金鑰）；
若你用環境變數覆寫過，設定同名的 ECPAY_HASH_KEY／ECPAY_HASH_IV 再執行即可。
"""
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime

BACKEND = os.environ.get("BACKEND_URL", "http://localhost:8080")
PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "..", "backend", "src", "main", "resources", "application.properties")
TEST_MEMBER = ("wendy-test@local.test", "Admin@12345")


def read_prop(key, env_name):
    if os.environ.get(env_name):
        return os.environ[env_name]
    with open(PROPS, encoding="utf-8") as f:
        for line in f:
            if line.startswith(key + "="):
                value = line.split("=", 1)[1].strip()
                m = re.match(r"\$\{[A-Za-z_]+:(.*)\}$", value)
                return m.group(1) if m else value
    raise SystemExit(f"找不到設定：{key}")


def url_encode(value):
    """
    對齊 EcpayService.urlEncode()：Java URLEncoder（x-www-form-urlencoded）
    → 轉小寫 → 還原 .NET UrlEncode 不會跳脫的符號。

    本腳本送出的欄位只含英數與 / : 空白 底線，這些字元在 Java 與 Python
    的 form 編碼結果一致；若日後加入 ~ 或 * 等字元要重新確認兩邊行為。
    """
    encoded = urllib.parse.quote_plus(value, safe="").lower()
    for old, new in (("%2d", "-"), ("%5f", "_"), ("%2e", "."),
                     ("%21", "!"), ("%2a", "*"), ("%28", "("), ("%29", ")")):
        encoded = encoded.replace(old, new)
    return encoded


def check_mac_value(params, hash_key, hash_iv):
    """綠界演算法：排序 → 前後包 HashKey/HashIV → URL encode 轉小寫 → SHA256 → 轉大寫"""
    raw = "HashKey=" + hash_key
    for key in sorted(params):
        raw += f"&{key}={params[key]}"
    raw += "&HashIV=" + hash_iv
    return hashlib.sha256(url_encode(raw).encode("utf-8")).hexdigest().upper()


def post_json(path, payload, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BACKEND + path, data=json.dumps(payload).encode(), headers=headers)
    return json.loads(urllib.request.urlopen(req).read())


def get_json(path, token):
    req = urllib.request.Request(BACKEND + path, headers={"Authorization": "Bearer " + token})
    return json.loads(urllib.request.urlopen(req).read())


def login():
    return post_json("/api/auth/login", {"email": TEST_MEMBER[0], "password": TEST_MEMBER[1]})["accessToken"]


def pick_unpaid_order(token):
    orders = get_json("/api/orders", token)
    unpaid = [o for o in orders if o["status"] == "UNPAID"]
    if not unpaid:
        raise SystemExit("找不到未付款訂單——請先在前端走一次結帳，或用參數指定交易編號")
    return unpaid[0]


def main():
    failed = "--fail" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]

    token = login()
    if args:
        trade_no, amount, order_id = args[0], 0, None
    else:
        order = pick_unpaid_order(token)
        trade_no, amount, order_id = order["merchantTradeNo"], order["totalAmount"], order["orderId"]
        print(f"目標訂單 #{order_id}：{trade_no}（{amount} 元，UNPAID）")

    now = datetime.now().strftime("%Y/%m/%d %H:%M:%S")
    params = {
        "MerchantID": read_prop("app.ecpay.merchant-id", "ECPAY_MERCHANT_ID"),
        "MerchantTradeNo": trade_no,
        "TradeNo": "SIM" + datetime.now().strftime("%y%m%d%H%M%S"),
        "RtnCode": "0" if failed else "1",
        "RtnMsg": "Failed" if failed else "Succeeded",
        "PaymentType": "Credit_CreditCard",
        "PaymentDate": now,
        "TradeDate": now,
        "TradeAmt": str(int(float(amount))) if amount else "0",
        "SimulatePaid": "1",
    }
    params["CheckMacValue"] = check_mac_value(
        params,
        read_prop("app.ecpay.hash-key", "ECPAY_HASH_KEY"),
        read_prop("app.ecpay.hash-iv", "ECPAY_HASH_IV"))

    req = urllib.request.Request(
        BACKEND + "/api/payments/ecpay/notify",
        data=urllib.parse.urlencode(params).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    body = urllib.request.urlopen(req).read().decode()
    # 綠界規定：收到 1|OK 才算通知成功，否則會重送
    print(f"後端回應：{body}")

    if order_id:
        after = next((o for o in get_json("/api/orders", token) if o["orderId"] == order_id), None)
        expected = "FAILED" if failed else "PAID"
        status = after["status"] if after else "?"
        mark = "OK  " if status == expected else "FAIL"
        print(f"{mark} 訂單 #{order_id} 狀態：UNPAID → {status}（預期 {expected}）")


if __name__ == "__main__":
    main()
