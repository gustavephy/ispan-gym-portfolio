/**
 * E2E 前置：把開發資料庫整個重建成「只有種子資料」的乾淨狀態，再啟動後端。
 *
 * 為什麼不用 Playwright 的 globalSetup：webServer 外掛會比 globalSetup 先啟動後端，
 * 在已連線的後端底下 DROP DATABASE 會把它弄壞；重建後也必須重啟後端，Hibernate
 * (ddl-auto=update) 才會建表、種子 runner 才會重新播種。所以獨立成一支腳本，
 * 由 `npm run test:e2e:fresh` 在 `playwright test` 之前執行。
 *
 * 流程：關掉佔用 8080 的後端 → DROP + CREATE 同名空庫 → 背景啟動後端（載入專案根目錄 .env）
 *      → 等到 /api/products 回 200 → 交棒給 playwright（config 的 reuseExistingServer 會沿用）。
 *
 * 會清掉所有非種子資料（手動註冊的帳號、訂單、退款、報表快照…），只在本機開發環境使用。
 * 必須帶 --yes 才會真的執行，避免手滑。
 */
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '..', '..')
const BACKEND = path.join(ROOT, 'backend')
const DB_NAME = process.env.E2E_DB_NAME || 'ispan-proj-gymdb'
const PORT = 8080
const HEALTH_URL = `http://localhost:${PORT}/api/products?page=0&size=1`
const START_TIMEOUT_MS = 5 * 60 * 1000

if (!process.argv.includes('--yes')) {
  console.error(`此腳本會刪除並重建資料庫 [${DB_NAME}]，請加上 --yes 明確確認。`)
  process.exit(2)
}

/** 專案根目錄 .env 只有後端讀，這裡自己載一次（不覆蓋已存在的環境變數） */
function loadDotEnv() {
  const file = path.join(ROOT, '.env')
  if (!fs.existsSync(file)) return
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/)
    if (!m || m[1].startsWith('#')) continue
    if (process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^(["'])(.*)\1$/, '$2')
  }
}

function log(msg) {
  console.log(`[reset-db] ${msg}`)
}

/** 找出佔用 PORT 的 java 行程並結束（mvnw 的 wrapper 死了 java 子行程常常還活著） */
function stopBackend() {
  if (process.platform === 'win32') {
    const out = spawnSync('netstat', ['-ano'], { encoding: 'utf8' }).stdout || ''
    const pids = new Set()
    for (const line of out.split(/\r?\n/)) {
      if (line.includes(`:${PORT}`) && /LISTENING/.test(line)) {
        const pid = line.trim().split(/\s+/).pop()
        if (pid && pid !== '0') pids.add(pid)
      }
    }
    for (const pid of pids) {
      const name = (spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], { encoding: 'utf8' }).stdout || '')
      if (!/java/i.test(name)) {
        log(`port ${PORT} 被非 java 行程 ${pid} 佔用，不動它：${name.trim()}`)
        continue
      }
      spawnSync('taskkill', ['/PID', pid, '/F'], { encoding: 'utf8' })
      log(`已結束後端 java 行程 ${pid}`)
    }
  } else {
    const out = spawnSync('sh', ['-c', `lsof -ti tcp:${PORT} || true`], { encoding: 'utf8' }).stdout || ''
    for (const pid of out.split(/\s+/).filter(Boolean)) {
      spawnSync('kill', ['-9', pid])
      log(`已結束行程 ${pid}`)
    }
  }
}

function resetDatabase() {
  const password = process.env.DB_PASSWORD
  if (!password) {
    console.error('缺少 DB_PASSWORD（環境變數或專案根目錄 .env）。')
    process.exit(2)
  }
  const sql = [
    `IF DB_ID('${DB_NAME}') IS NOT NULL BEGIN`,
    `  ALTER DATABASE [${DB_NAME}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;`,
    `  DROP DATABASE [${DB_NAME}];`,
    'END',
    `CREATE DATABASE [${DB_NAME}];`,
  ].join(' ')
  const r = spawnSync(
    'sqlcmd',
    ['-S', process.env.DB_HOST || 'localhost', '-U', process.env.DB_USERNAME || 'sa', '-P', password, '-d', 'master', '-C', '-b', '-Q', sql],
    { encoding: 'utf8' },
  )
  if (r.status !== 0) {
    console.error(r.stdout, r.stderr)
    console.error('資料庫重建失敗（SQL Server 沒開？密碼不對？）。')
    process.exit(1)
  }
  log(`資料庫 [${DB_NAME}] 已重建為空庫`)
}

const BACKEND_LOG = path.join(HERE, '.backend.log')

/**
 * 背景啟動後端。輸出寫到 e2e/.backend.log（已 gitignore）而不是丟掉——啟動失敗時
 * 才有東西可看；等待就緒逾時的錯誤訊息也會指到這個檔。
 */
function startBackend() {
  const isWin = process.platform === 'win32'
  // 不靠 console handle：detached／無視窗的 java 子行程連 cmd 的 > 重導向都接不到（實測 log 0 行）。
  // 改用 Spring Boot 的 LOGGING_FILE_NAME（= logging.file.name）讓應用程式自己把 log 寫進檔案，
  // 啟動失敗的堆疊也會在裡面；Maven 本身的輸出不重要，丟掉無妨。
  const childEnv = { ...process.env, LOGGING_FILE_NAME: BACKEND_LOG }
  fs.writeFileSync(BACKEND_LOG, '')
  let child
  if (isWin) {
    // Windows：
    // - cmd /c 不會從 cwd 解析裸的 mvnw.cmd（實測回「不是內部或外部命令」），要給絕對路徑
    // - detached 模式下 Maven 再開的 java 子行程接不到 Node 傳下去的檔案 handle，log 會是空的，
    //   改由 cmd 自己做 > log 2>&1 重導向；windowsVerbatimArguments 讓這串指令原樣交給 cmd
    const cmdLine = `/c ""${path.join(BACKEND, 'mvnw.cmd')}" spring-boot:run"`
    child = spawn('cmd.exe', [cmdLine], {
      cwd: BACKEND, detached: true, stdio: 'ignore', windowsHide: true, windowsVerbatimArguments: true, env: childEnv,
    })
  } else {
    child = spawn(path.join(BACKEND, 'mvnw'), ['spring-boot:run'], {
      cwd: BACKEND, detached: true, stdio: 'ignore', env: childEnv,
    })
  }
  child.on('exit', (code) => {
    // 正常情況下後端會一直跑，這裡不該被觸發；被觸發代表啟動失敗，讓等待迴圈提早放棄
    backendExited = code ?? 1
  })
  child.unref()
  log(`後端已在背景啟動（Hibernate 建表 + 種子播種，首次約 30 秒；輸出見 ${BACKEND_LOG}）`)
}
let backendExited = null

async function waitForBackend() {
  const deadline = Date.now() + START_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (backendExited !== null) {
      console.error(`後端行程提早結束（exit ${backendExited}），請看 ${BACKEND_LOG}`)
      process.exit(1)
    }
    try {
      const res = await fetch(HEALTH_URL)
      if (res.ok) {
        log('後端已就緒')
        return
      }
    } catch {
      // 還沒起來，繼續等
    }
    await new Promise((r) => setTimeout(r, 3000))
  }
  console.error(`等待後端啟動逾時（5 分鐘），請看 ${BACKEND_LOG}。`)
  process.exit(1)
}

loadDotEnv()
stopBackend()
await new Promise((r) => setTimeout(r, 2000)) // 讓 port 與 DB 連線真的釋放
resetDatabase()
startBackend()
await waitForBackend()

// --then-test：由本腳本接著啟動 playwright，讓 .env 載進來的變數（DB_PASSWORD 等，
// admin-product／subscription spec 裡的 sqlcmd 需要）一路傳給測試行程。
// 用 npm script 的 && 串接做不到這件事——那是兩個獨立行程。
// `--then-test` 之後的參數原樣轉給 playwright（例如只跑某支 spec）。
const thenTest = process.argv.indexOf('--then-test')
if (thenTest === -1) {
  log('完成。接下來的 playwright test 會沿用這個已啟動的後端（記得自己帶 DB_PASSWORD）。')
} else {
  const extra = process.argv.slice(thenTest + 1)
  log(`啟動 playwright test ${extra.join(' ')}`)
  const runner = spawn(
    process.platform === 'win32' ? 'npx.cmd' : 'npx',
    ['playwright', 'test', ...extra],
    { cwd: path.resolve(HERE, '..'), stdio: 'inherit', env: process.env, shell: process.platform === 'win32' },
  )
  runner.on('exit', (code) => process.exit(code ?? 1))
}
