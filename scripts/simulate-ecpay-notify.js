#!/usr/bin/env node
/**
 * 模擬綠界的付款結果通知（server-to-server callback），直接打進本機後端。
 *
 * 為什麼需要：本機開發時 ReturnURL 指向 localhost，綠界的伺服器連不進來
 * （見 EcpayService 的類別註解），所以在測試頁按下「交易成功」後，通知不會
 * 送達、訂單一直停在 UNPAID。這支腳本用與後端相同的 CheckMacValue 演算法
 * 自行簽章並送出通知，讓訂單推進到 PAID 並觸發履約（發放課程堂數、啟用訂閱），
 * 不必架 ngrok。
 *
 * 跟 simulate-ecpay-notify.py 是同一支腳本的兩種語言版本，行為完全對齊；
 * 差別只在這支用 Node.js（專案本來就有裝，零額外套件依賴），沒裝 Python
 * 的人可以直接用這支，不用另外安裝任何東西。
 *
 * 用法：
 *     node scripts/simulate-ecpay-notify.js                    # 自動挑最新一筆未付款訂單
 *     node scripts/simulate-ecpay-notify.js GYM20260818150012833
 *     node scripts/simulate-ecpay-notify.js --fail              # 模擬付款失敗（訂單轉 FAILED）
 *
 * 金鑰預設讀 application.properties 裡 ${ENV:預設值} 的預設值（綠界測試環境的公開金鑰）；
 * 若你用環境變數覆寫過，設定同名的 ECPAY_HASH_KEY／ECPAY_HASH_IV 再執行即可。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');

const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const PROPS_PATH = path.join(__dirname, '..', 'backend', 'src', 'main', 'resources', 'application.properties');
const TEST_MEMBER = { email: 'wendy-test@local.test', password: 'Admin@12345' };

function readProp(key, envName) {
  if (process.env[envName]) return process.env[envName];
  const lines = fs.readFileSync(PROPS_PATH, 'utf-8').split('\n');
  for (const line of lines) {
    if (line.startsWith(key + '=')) {
      const value = line.split('=').slice(1).join('=').trim();
      const m = value.match(/^\$\{[A-Za-z_]+:(.*)\}$/);
      return m ? m[1] : value;
    }
  }
  throw new Error(`找不到設定：${key}`);
}

// 對齊 EcpayService.urlEncode()：Java URLEncoder（x-www-form-urlencoded）
// → 轉小寫 → 還原 .NET UrlEncode 不會跳脫的符號
function javaUrlEncode(str) {
  const bytes = Buffer.from(str, 'utf8');
  let out = '';
  for (const b of bytes) {
    const c = String.fromCharCode(b);
    if (b < 128 && /[A-Za-z0-9.\-*_]/.test(c)) out += c;
    else if (b === 0x20) out += '+';
    else out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
  }
  return out;
}

function ecpayUrlEncode(value) {
  const encoded = javaUrlEncode(value).toLowerCase();
  return encoded
    .replace(/%2d/g, '-')
    .replace(/%5f/g, '_')
    .replace(/%2e/g, '.')
    .replace(/%21/g, '!')
    .replace(/%2a/g, '*')
    .replace(/%28/g, '(')
    .replace(/%29/g, ')');
}

// 綠界演算法：排序 → 前後包 HashKey/HashIV → URL encode 轉小寫 → SHA256 → 轉大寫
function checkMacValue(params, hashKey, hashIv) {
  const sortedKeys = Object.keys(params).sort();
  let raw = 'HashKey=' + hashKey;
  for (const k of sortedKeys) raw += `&${k}=${params[k]}`;
  raw += '&HashIV=' + hashIv;
  return crypto.createHash('sha256').update(ecpayUrlEncode(raw), 'utf8').digest('hex').toUpperCase();
}

function request(method, urlPath, { body, token, form } = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(BACKEND + urlPath);
    const headers = {};
    let data;
    if (form) {
      data = new URLSearchParams(form).toString();
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
    } else if (body) {
      data = JSON.stringify(body);
      headers['Content-Type'] = 'application/json';
    }
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const req = http.request(url, { method, headers }, (res) => {
      let chunks = '';
      res.on('data', (c) => (chunks += c));
      res.on('end', () => {
        try {
          resolve(JSON.parse(chunks));
        } catch {
          resolve(chunks);
        }
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

async function login() {
  const res = await request('POST', '/api/auth/login', {
    body: { email: TEST_MEMBER.email, password: TEST_MEMBER.password },
  });
  return res.accessToken;
}

async function pickUnpaidOrder(token) {
  const orders = await request('GET', '/api/orders', { token });
  const unpaid = orders.filter((o) => o.status === 'UNPAID');
  if (unpaid.length === 0) {
    throw new Error('找不到未付款訂單——請先在前端走一次結帳，或用參數指定交易編號');
  }
  return unpaid[0];
}

async function main() {
  const failed = process.argv.includes('--fail');
  const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));

  const token = await login();
  let tradeNo, amount, orderId;
  if (args.length > 0) {
    [tradeNo, amount, orderId] = [args[0], 0, null];
  } else {
    const order = await pickUnpaidOrder(token);
    tradeNo = order.merchantTradeNo;
    amount = order.totalAmount;
    orderId = order.orderId;
    console.log(`目標訂單 #${orderId}：${tradeNo}（${amount} 元，UNPAID）`);
  }

  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const dateStr = `${now.getFullYear()}/${pad(now.getMonth() + 1)}/${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;

  const params = {
    MerchantID: readProp('app.ecpay.merchant-id', 'ECPAY_MERCHANT_ID'),
    MerchantTradeNo: tradeNo,
    TradeNo: 'SIM' + now.toISOString().replace(/[-:T.Z]/g, '').slice(0, 12),
    RtnCode: failed ? '0' : '1',
    RtnMsg: failed ? 'Failed' : 'Succeeded',
    PaymentType: 'Credit_CreditCard',
    PaymentDate: dateStr,
    TradeDate: dateStr,
    TradeAmt: amount ? String(Math.trunc(amount)) : '0',
    SimulatePaid: '1',
  };
  params.CheckMacValue = checkMacValue(
    params,
    readProp('app.ecpay.hash-key', 'ECPAY_HASH_KEY'),
    readProp('app.ecpay.hash-iv', 'ECPAY_HASH_IV'),
  );

  const body = await request('POST', '/api/payments/ecpay/notify', { form: params });
  // 綠界規定：收到 1|OK 才算通知成功，否則會重送
  console.log(`後端回應：${body}`);

  if (orderId) {
    const after = (await request('GET', '/api/orders', { token })).find((o) => o.orderId === orderId);
    const expected = failed ? 'FAILED' : 'PAID';
    const status = after ? after.status : '?';
    const mark = status === expected ? 'OK  ' : 'FAIL';
    console.log(`${mark} 訂單 #${orderId} 狀態：UNPAID → ${status}（預期 ${expected}）`);
  }
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
