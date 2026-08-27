import { test as base, expect, request as playwrightRequest } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const API = 'http://localhost:8080'

/** 跨測試回合共用的登入快取檔（已列入 .gitignore） */
const CACHE_FILE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '.auth-cache.json',
)

/**
 * 測試帳號。密碼由 TestDataSeedRunner 統一播種為 Admin@12345，
 * 內容見該類別的 Javadoc（四個固定 email、身分組合各不相同）。
 */
export const ACCOUNTS = {
  /** 純會員：沒有任何員工身分 */
  member: { email: 'wendy-test@local.test', roleLabel: '會員' },
  /** 會員＋管理員 */
  admin: { email: 'emp-admin@local.test', roleLabel: '管理員' },
  /** 會員＋員工＋教練（三重身分，徽章顯示教練） */
  trainer: { email: 'emp-trainer@local.test', roleLabel: '教練' },
  /**
   * 純管理員，名下沒有 member 列。
   * 專門用來守住「非會員被會員專屬 API 的 401 誤登出」這個回歸——
   * 這個 bug 前後被三個人各自發現過一次（HomeCourses／CourseView／SubscriptionView）。
   */
  adminOnly: { email: 'admin-only@local.test', roleLabel: '管理員' },
}

export const PASSWORD = 'Admin@12345'

/**
 * 登入回應快取。
 *
 * <p>後端對登入有限流且門檻寫死在 AuthController：同一 IP 每分鐘 10 次、每小時 30 次，
 * 同一 email 每 15 分鐘 5 次。每個測試各自從 UI 登入很快就會撞到 429
 *（實測跑第二輪就整批失敗），所以同一個帳號只真的登入一次，之後重用。
 */
function readCache() {
  try {
    return JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'))
  } catch {
    return {}
  }
}

function writeCache(cache) {
  try {
    fs.writeFileSync(CACHE_FILE, JSON.stringify(cache, null, 2))
  } catch {
    // 寫不進去只是失去跨回合快取，不該讓測試失敗
  }
}

async function fetchSession(account) {
  const cache = readCache()
  if (cache[account.email]) {
    // token 仍有效就直接用；過期或帳號被停用時後端會回 401，這時重新登入
    const ctx = await playwrightRequest.newContext()
    const probe = await ctx.get(`${API}/api/products?page=0&size=1`, {
      headers: { Authorization: `Bearer ${cache[account.email].accessToken}` },
    })
    await ctx.dispose()
    if (probe.status() !== 401) return cache[account.email]
  }

  const ctx = await playwrightRequest.newContext()
  const res = await ctx.post(`${API}/api/auth/login`, {
    data: { email: account.email, password: PASSWORD },
  })
  const body = await res.json()
  await ctx.dispose()

  if (!res.ok()) {
    throw new Error(
      `登入失敗（${res.status()}）：${body.message ?? ''}\n` +
        'LOGIN_RATE_LIMITED 代表短時間內登入次數過多（同 IP 每分鐘 10 次、每小時 30 次，\n' +
        '門檻寫死在 AuthController）。等待或重啟後端即可重置——限流是記憶體狀態。',
    )
  }
  writeCache({ ...cache, [account.email]: body })
  return body
}

/**
 * 讓瀏覽器以指定帳號登入。
 *
 * <p>走 API 取得 token 後直接寫進 localStorage（鍵名 'auth'，格式同
 * stores/auth.js 的 persist()），不經過登入畫面——這樣既避開限流，
 * 也讓測試不必為了到達某個頁面而重複走一次登入流程。
 *
 * <p>要驗證「登入畫面本身」的行為請用 loginViaForm()。
 */
export async function loginAs(page, account) {
  const session = await fetchSession(account)

  await page.goto('/')
  await page.evaluate((s) => {
    localStorage.setItem(
      'auth',
      JSON.stringify({
        token: s.accessToken,
        accountId: s.accountId ?? null,
        email: s.email ?? '',
        roles: s.roles ?? [],
      }),
    )
  }, session)
  await page.reload()

  await expect(page.getByTitle(account.email)).toBeVisible()
}

/** 真的走登入表單；只給驗證登入流程本身的測試使用，會消耗限流額度 */
export async function loginViaForm(page, account, password = PASSWORD) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('密碼').fill(password)
  await page.getByRole('button', { name: '登入', exact: true }).click()
}

/** 取得 API token，供需要直接呼叫後端的測試使用（例如模擬付款） */
export async function apiToken(account) {
  const session = await fetchSession(account)
  return session.accessToken
}

/**
 * 每個 spec 專屬的唯一字串，避免共用開發資料庫時互相污染。
 * 例：uniqueSuffix() → '0827-093512-841'
 */
export function uniqueSuffix() {
  const now = new Date()
  const pad = (n, w = 2) => String(n).padStart(w, '0')
  return [
    `${pad(now.getMonth() + 1)}${pad(now.getDate())}`,
    `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`,
    pad(now.getMilliseconds(), 3),
  ].join('-')
}

export const test = base
export { expect }
