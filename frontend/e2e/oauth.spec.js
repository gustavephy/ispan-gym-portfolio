import { test, expect } from './fixtures.js'

/**
 * 第三批 Google OAuth 最小垂直流程的前端測試。
 *
 * 除了「bogus code 打真實 /api/auth/oauth/exchange」那一案是真的前後端往返，
 * 其餘都用 page.route() 攔截 /api 與 /oauth2，不需要真實 Google——真實 Google
 * 帳號 E2E 由人工在真實瀏覽器完成（Claude 不得碰 Google 登入）。
 *
 * 後端契約（讀 controller/DTO 原始碼，docs/API-REFERENCE.md 尚無 OAuth 章節）：
 *   POST /api/auth/oauth/exchange {code}
 *     → 200 {accessToken, accountId, email, roles, returnPath}
 *     → 400 OAUTH_EXCHANGE_CODE_INVALID（不存在／已用／已過期／帳號不可登入，皆同一句）
 *   POST /api/auth/oauth/google/registration/complete {registrationToken,...} → 200 {code}
 *     → 400 OAUTH_REGISTRATION_TICKET_INVALID / 400 INVALID_REQUEST / 409 OAUTH_ACCOUNT_LINK_REQUIRED
 */

const EXCHANGE = '**/api/auth/oauth/exchange'
const COMPLETE = '**/api/auth/oauth/google/registration/complete'

function apiError(code, message, status) {
  return {
    status,
    contentType: 'application/json',
    body: JSON.stringify({ code, message, timestamp: '2026-01-01T00:00:00' }),
  }
}

/** 讀瀏覽器裡 stores/auth.js persist() 的內容 */
function storedAuth(page) {
  return page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('auth') || 'null')
    } catch {
      return null
    }
  })
}

// 成功流程測完要導到某個頁面。用 /member-register：它是公開路由、掛載時不打任何 API，
// 所以 mock 出來的假 JWT 不會去打真實後端而被 401 連帶登出，session 斷言才穩定。
// （真正的導頁規則測試看「returnPath」相關案例；這裡只是要一個中性的落地頁。）
const NEUTRAL_LANDING = '/member-register'

test.describe('C3 Google OAuth 前端流程', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.clear())
  })

  // ── 登入頁的 Google 按鈕 ───────────────────────────────────────────────
  test('登入頁顯示 Google 按鈕與資料揭露，且不承諾解除綁定', async ({ page }) => {
    await page.goto('/login')
    const btn = page.getByRole('button', { name: '使用 Google 登入' })
    await expect(btn).toBeVisible()
    const hint = page.getByText('授權本站讀取您 Google 帳號')
    await expect(hint).toBeVisible()
    await expect(hint).not.toContainText('解除綁定')
  })

  test('點 Google 按鈕導向 /oauth2/authorization/google（不含 /api 前綴）', async ({ page }) => {
    let seen = ''
    await page.route('**/oauth2/authorization/google*', (route) => {
      seen = route.request().url()
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'stub' })
    })
    await page.goto('/login')
    await page.getByRole('button', { name: '使用 Google 登入' }).click()
    await expect.poll(() => seen).toContain('/oauth2/authorization/google')
    expect(seen).not.toContain('/api/oauth2')
  })

  test('帶 redirect 時發起網址帶上 returnPath', async ({ page }) => {
    let seen = ''
    await page.route('**/oauth2/authorization/google*', (route) => {
      seen = route.request().url()
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'stub' })
    })
    await page.goto('/login?redirect=/cart')
    await page.getByRole('button', { name: '使用 Google 登入' }).click()
    await expect.poll(() => seen).toContain('returnPath=%2Fcart')
  })

  // ── 失敗導回 /login?oauthError= ──────────────────────────────────────
  test('oauthError=access_denied 顯示取消訊息並把 query 清掉', async ({ page }) => {
    await page.goto('/login?oauthError=access_denied')
    await expect(page.getByText('您已取消 Google 登入。')).toBeVisible()
    await expect(page).toHaveURL('/login')
  })

  test('oauthError=account_disabled 顯示停用訊息', async ({ page }) => {
    await page.goto('/login?oauthError=account_disabled')
    await expect(page.getByText('此帳號已被停用，如有疑問請聯絡管理員。')).toBeVisible()
  })

  test('未知的 oauthError 顯示通用 fallback 文案', async ({ page }) => {
    await page.goto('/login?oauthError=some_new_reason')
    await expect(page.getByText('Google 登入未完成，請再試一次。')).toBeVisible()
  })

  // ── /oauth/callback ─────────────────────────────────────────────────
  test('callback 無 code：顯示錯誤、不建立 session', async ({ page }) => {
    await page.goto('/oauth/callback')
    await expect(page.getByText('登入連結無效或已失效，請重新使用 Google 登入。')).toBeVisible()
    expect(await storedAuth(page)).toBeNull()
  })

  test('callback exchange 成功：寫入既有 auth key 並依 returnPath 導頁', async ({ page }) => {
    await page.route(EXCHANGE, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'jwt-abc',
          accountId: 321,
          email: 'g@example.com',
          roles: ['MEMBER'],
          returnPath: NEUTRAL_LANDING,
        }),
      }),
    )
    await page.goto('/oauth/callback?code=good-code')
    await expect(page).toHaveURL(NEUTRAL_LANDING)
    const auth = await storedAuth(page)
    expect(auth).toMatchObject({ token: 'jwt-abc', accountId: 321, roles: ['MEMBER'] })
    // 沒有第二組 OAuth 專屬 storage
    const keys = await page.evaluate(() => Object.keys(localStorage))
    expect(keys.filter((k) => /oauth/i.test(k))).toHaveLength(0)
  })

  test('callback exchange 回 400 OAUTH_EXCHANGE_CODE_INVALID：固定文案、無 session', async ({ page }) => {
    await page.route(EXCHANGE, (route) =>
      route.fulfill(apiError('OAUTH_EXCHANGE_CODE_INVALID', '交換碼無效或已過期，請重新使用 Google 登入。', 400)),
    )
    await page.goto('/oauth/callback?code=used-code')
    await expect(page.getByText('登入驗證碼無效或已過期，請重新使用 Google 登入。')).toBeVisible()
    await expect(page.getByRole('button', { name: '重新使用 Google 登入' })).toBeVisible()
    expect(await storedAuth(page)).toBeNull()
  })

  test('callback exchange 網路失敗：顯示通用文案、不外洩技術訊息', async ({ page }) => {
    await page.route(EXCHANGE, (route) => route.abort('failed'))
    await page.goto('/oauth/callback?code=whatever')
    await expect(page.getByText('Google 登入服務暫時無法使用，請稍後再試。')).toBeVisible()
    await expect(page.getByText(/network|error|xhr/i)).toHaveCount(0)
  })

  test('callback：進頁後網址列的 code 已被清掉', async ({ page }) => {
    await page.route(EXCHANGE, (route) => route.fulfill(apiError('OAUTH_EXCHANGE_CODE_INVALID', 'x', 400)))
    await page.goto('/oauth/callback?code=secret-code-123')
    await expect(page.getByRole('button', { name: '重新使用 Google 登入' })).toBeVisible()
    expect(page.url()).not.toContain('secret-code-123')
    expect(page.url()).not.toContain('code=')
  })

  // ── /oauth/register 補資料 ──────────────────────────────────────────
  test('register 無 token：終端狀態、看不到表單', async ({ page }) => {
    await page.goto('/oauth/register')
    await expect(page.getByText('註冊連結無效或已失效，請重新使用 Google 登入。')).toBeVisible()
    await expect(page.getByLabel('手機號碼')).toHaveCount(0)
  })

  test('register 有 token：顯示補資料表單', async ({ page }) => {
    await page.goto('/oauth/register?token=tkn-1')
    await expect(page.getByRole('heading', { name: '完成 Google 註冊' })).toBeVisible()
    await expect(page.getByLabel('手機號碼')).toBeVisible()
    await expect(page.getByText('查看 Google 綁定狀態')).not.toContainText('解除綁定')
    expect(page.url()).not.toContain('tkn-1')
  })

  test('register 全程成功：complete → exchange → 寫 session → 導頁', async ({ page }) => {
    await page.route(COMPLETE, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'ex-1' }) }),
    )
    await page.route(EXCHANGE, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: 'jwt-reg',
          accountId: 999,
          email: 'new@example.com',
          roles: ['MEMBER'],
          returnPath: NEUTRAL_LANDING,
        }),
      }),
    )
    await page.goto('/oauth/register?token=tkn-2')
    await page.getByLabel('姓氏').fill('王')
    await page.getByLabel('名字').fill('小明')
    await page.getByLabel('手機號碼').fill('0912345678')
    await page.getByRole('button', { name: '建立會員帳號' }).click()
    await expect(page).toHaveURL(NEUTRAL_LANDING)
    expect(await storedAuth(page)).toMatchObject({ token: 'jwt-reg', accountId: 999 })
  })

  test('register：complete 回 409 ACCOUNT_LINK_REQUIRED → 終端、CTA 前往登入、表單消失', async ({ page }) => {
    await page.route(COMPLETE, (route) =>
      route.fulfill(apiError('OAUTH_ACCOUNT_LINK_REQUIRED', 'x', 409)),
    )
    await page.goto('/oauth/register?token=tkn-3')
    await page.getByLabel('姓氏').fill('王')
    await page.getByLabel('名字').fill('小明')
    await page.getByLabel('手機號碼').fill('0912345678')
    await page.getByRole('button', { name: '建立會員帳號' }).click()
    await expect(page.getByText('這個 Email 已有本站帳號，請使用原本的登入方式登入。')).toBeVisible()
    await expect(page.getByRole('button', { name: '前往登入' })).toBeVisible()
    await expect(page.getByLabel('姓氏')).toHaveCount(0)
  })

  test('register：complete 回 500 → 終端 CTA Google、無法再送 ticket', async ({ page }) => {
    let completeCalls = 0
    await page.route(COMPLETE, (route) => {
      completeCalls += 1
      return route.fulfill(apiError('INTERNAL_ERROR', '系統發生錯誤，請稍後再試', 500))
    })
    await page.goto('/oauth/register?token=tkn-4')
    await page.getByLabel('姓氏').fill('王')
    await page.getByLabel('名字').fill('小明')
    await page.getByLabel('手機號碼').fill('0912345678')
    await page.getByRole('button', { name: '建立會員帳號' }).click()
    await expect(page.getByText('Google 註冊暫時無法完成，請重新使用 Google 登入。')).toBeVisible()
    await expect(page.getByRole('button', { name: '重新使用 Google 登入' })).toBeVisible()
    await expect(page.getByLabel('姓氏')).toHaveCount(0)
    expect(completeCalls).toBe(1)
  })

  test('register：complete 回 400 INVALID_REQUEST → 留在表單讓使用者修正', async ({ page }) => {
    await page.route(COMPLETE, (route) =>
      route.fulfill(apiError('INVALID_REQUEST', '電話格式不正確，請輸入台灣手機號碼', 400)),
    )
    await page.goto('/oauth/register?token=tkn-5')
    await page.getByLabel('姓氏').fill('王')
    await page.getByLabel('名字').fill('小明')
    await page.getByLabel('手機號碼').fill('0912345678')
    await page.getByRole('button', { name: '建立會員帳號' }).click()
    await expect(page.getByText('電話格式不正確，請輸入台灣手機號碼')).toBeVisible()
    // 表單還在
    await expect(page.getByLabel('姓氏')).toBeVisible()
  })

  test('register 生日：今天不可送出、昨天可以', async ({ page }) => {
    await page.route(COMPLETE, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'ex-x' }) }),
    )
    await page.route(EXCHANGE, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ accessToken: 't', accountId: 1, email: 'e', roles: [], returnPath: null }),
      }),
    )
    const today = new Date()
    const yesterday = new Date(Date.now() - 86400000)
    const fmt = (d) =>
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

    await page.goto('/oauth/register?token=tkn-6')
    await page.getByLabel('姓氏').fill('王')
    await page.getByLabel('名字').fill('小明')
    await page.getByLabel('手機號碼').fill('0912345678')

    await page.getByLabel('生日（選填）').fill(fmt(today))
    await expect(page.getByText('生日必須是過去的日期')).toBeVisible()
    await expect(page.getByRole('button', { name: '建立會員帳號' })).toBeDisabled()

    await page.getByLabel('生日（選填）').fill(fmt(yesterday))
    await expect(page.getByText('生日必須是過去的日期')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '建立會員帳號' })).toBeEnabled()
  })

  // ── 真實前後端往返（唯一一案不 mock）─────────────────────────────────
  test('bogus code 打真實 /api/auth/oauth/exchange → 真的回 400、前端顯示固定文案', async ({ page }) => {
    await page.goto('/oauth/callback?code=definitely-not-a-real-code')
    await expect(page.getByText('登入驗證碼無效或已過期，請重新使用 Google 登入。')).toBeVisible()
    expect(await storedAuth(page)).toBeNull()
  })

  // ── RWD ─────────────────────────────────────────────────────────────
  test('手機版：登入頁 Google 區塊與 register 表單無水平溢出', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })

    await page.goto('/login')
    await expect(page.getByRole('button', { name: '使用 Google 登入' })).toBeVisible()
    let overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    )
    expect(overflow).toBe(false)

    await page.goto('/oauth/register?token=rwd-1')
    await expect(page.getByLabel('手機號碼')).toBeVisible()
    overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    )
    expect(overflow).toBe(false)
  })
})
