#!/usr/bin/env node
/**
 * 背景監控：每隔幾秒查一次資料庫，找到「監控啟動之後才建立」的 UNPAID 訂單，
 * 自動幫它模擬一次「付款成功」的綠界通知（簽章邏輯與 simulate-ecpay-notify.js
 * 完全相同、同一套 CheckMacValue 演算法）。
 *
 * 為什麼要限定「啟動之後才建立」：啟動前就存在的 UNPAID 訂單不會被動到，
 * 避免誤把你刻意留著測試「取消未付款訂單」之類流程的訂單也自動變成已付款。
 * 一筆訂單只要曾經被本腳本標記過（或用其他方式離開 UNPAID），
 * 就不會再被重複處理——判斷完全依賴當下的 status，不需要另外記錄。
 *
 * 只在本機終端機手動啟動時作用，Ctrl+C 隨時可以關掉；不會動到任何實際
 * 程式碼、也不影響綠界 ReturnURL 之類的正式流程設定。
 *
 * 用法：
 *   node scripts/watch-and-simulate-payment.js
 *   node scripts/watch-and-simulate-payment.js --interval 3000   # 輪詢間隔（毫秒），預設 3000
 *
 * 需要環境變數 DB_PASSWORD（本機 SQL Server sa 密碼），跟其他腳本一致。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');

const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const PROPS_PATH = path.join(__dirname, '..', 'backend', 'src', 'main', 'resources', 'application.properties');
const DB_PASSWORD = process.env.DB_PASSWORD;
if (!DB_PASSWORD) {
  console.error('缺少環境變數 DB_PASSWORD（本機 SQL Server sa 密碼）');
  process.exit(1);
}

const intervalArgIndex = process.argv.indexOf('--interval');
const POLL_INTERVAL_MS = intervalArgIndex !== -1 ? Number(process.argv[intervalArgIndex + 1]) : 3000;

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

function sqlQuery(sql) {
  const out = execFileSync(
    'sqlcmd',
    ['-S', 'localhost,1433', '-U', 'sa', '-P', DB_PASSWORD, '-d', 'ispan-proj-gymdb', '-C',
      '-h', '-1', '-W', '-s', '|', '-Q', 'SET NOCOUNT ON; ' + sql],
    { encoding: 'utf-8' },
  );
  return out
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split('|').map((c) => c.trim()));
}

// ---- CheckMacValue（與 EcpayService.java / simulate-ecpay-notify.js 同一套演算法）----
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
    .replace(/%2d/g, '-').replace(/%5f/g, '_').replace(/%2e/g, '.')
    .replace(/%21/g, '!').replace(/%2a/g, '*').replace(/%28/g, '(').replace(/%29/g, ')');
}
function checkMacValue(params, hashKey, hashIv) {
  const sortedKeys = Object.keys(params).sort();
  let raw = 'HashKey=' + hashKey;
  for (const k of sortedKeys) raw += `&${k}=${params[k]}`;
  raw += '&HashIV=' + hashIv;
  return crypto.createHash('sha256').update(ecpayUrlEncode(raw), 'utf8').digest('hex').toUpperCase();
}

function notify(form) {
  return new Promise((resolve, reject) => {
    const url = new URL(BACKEND + '/api/payments/ecpay/notify');
    const data = new URLSearchParams(form).toString();
    const req = http.request(
      url,
      { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
      (res) => {
        let chunks = '';
        res.on('data', (c) => (chunks += c));
        res.on('end', () => resolve(chunks));
      },
    );
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function simulatePaid(merchantTradeNo, totalAmount) {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const dateStr = `${now.getFullYear()}/${pad(now.getMonth() + 1)}/${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
  const params = {
    MerchantID: readProp('app.ecpay.merchant-id', 'ECPAY_MERCHANT_ID'),
    MerchantTradeNo: merchantTradeNo,
    TradeNo: 'AUTO' + now.toISOString().replace(/[-:T.Z]/g, '').slice(0, 12),
    RtnCode: '1',
    RtnMsg: 'Succeeded',
    PaymentType: 'Credit_CreditCard',
    PaymentDate: dateStr,
    TradeDate: dateStr,
    TradeAmt: String(Math.trunc(Number(totalAmount))),
    SimulatePaid: '1',
  };
  params.CheckMacValue = checkMacValue(
    params,
    readProp('app.ecpay.hash-key', 'ECPAY_HASH_KEY'),
    readProp('app.ecpay.hash-iv', 'ECPAY_HASH_IV'),
  );
  return notify(params);
}

async function poll(baselineId) {
  const rows = sqlQuery(
    `SELECT id, merchant_trade_no, total_amount FROM orders WHERE status='UNPAID' AND id > ${baselineId} ORDER BY id`,
  );
  for (const [id, merchantTradeNo, totalAmount] of rows) {
    console.log(`[${new Date().toLocaleTimeString('zh-TW')}] 偵測到新的未付款訂單 #${id}（${merchantTradeNo}，${totalAmount} 元），模擬付款成功中…`);
    try {
      const resp = await simulatePaid(merchantTradeNo, totalAmount);
      console.log(`  → 後端回應：${resp}`);
    } catch (err) {
      console.error(`  → 模擬失敗：${err.message || err}`);
    }
  }
}

async function main() {
  const [[baselineRaw]] = sqlQuery('SELECT ISNULL(MAX(id), 0) FROM orders');
  const baselineId = Number(baselineRaw);
  console.log(`監控啟動，起始訂單 id：${baselineId}（只處理之後才建立的新訂單）`);
  console.log(`每 ${POLL_INTERVAL_MS}ms 檢查一次，Ctrl+C 結束\n`);

  setInterval(() => {
    poll(baselineId).catch((err) => console.error('輪詢失敗：', err.message || err));
  }, POLL_INTERVAL_MS);
}

main();
