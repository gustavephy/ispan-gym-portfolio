import { test, expect, loginAs, ACCOUNTS } from './fixtures.js'

/**
 * 第五批「完整 OAuth 帳號設定」前端流程測試。
 *
 * <p>除了「密碼欄位規則」那類純前端行為，其餘都用 page.route() 攔截 /api 與 /oauth2，
 * 不需要真實 Google——真實 Google 帳號 E2E 由人工在真實瀏覽器完成（Claude 不得碰
 * Google 登入）。登入用 loginAs 直接寫 localStorage（避開限流），/api/account/me
 * 走真實後端，只 mock /api/account/me/oauth-connections 與綁定／解除／設定密碼三支。
 *
 * 後端契約（讀 controller/DTO/handler 原始碼 + docs/API-REFERENCE.md §2.10 set-password、§2.12 OAuthConnectionController）：
 *   GET    /api/account/me/oauth-connections → { google:{linked,email,linkedAt}, hasPassword }
 *   POST   /api/account/me/oauth/google/link {currentPassword} → 200 {authorizationUrl}
 *            / 401 REAUTHENTICATION_REQUIRED（密碼錯誤，不登出）/ 409 OAUTH_ALREADY_LINKED
 *   DELETE /api/account/me/oauth/google {currentPassword} → 204
 *            / 401 REAUTHENTICATION_REQUIRED / 409 LAST_LOGIN_METHOD
 *   POST   /api/account/me/set-password {newPassword,confirmPassword}
 *            → 200 {accessToken,accountId,email,roles} / 409 PASSWORD_ALREADY_CONFIGURED
 */

const CONNECTIONS = '**/api/account/me/oauth-connections'
const LINK = '**/api/account/me/oauth/google/link'
const UNLINK = '**/api/account/me/oauth/google'
const SET_PASSWORD = '**/api/account/me/set-password'

function apiError(code, message, status) {
  return {
    status,
    contentType: 'application/json',
    body: JSON.stringify({ code, message, timestamp: '2026-01-01T00:00:00' }),
  }
}

function json(body, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}

function storedAuth(page) {
  return page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('auth') || 'null')
    } catch {
      return null
    }
  })
}

/** 讓 GET oauth-connections 回指定內容（可傳陣列，依序回應每次呼叫） */
async function mockConnections(page, ...responses) {
  let i = 0
  await page.route(CONNECTIONS, (route) => {
    const body = responses[Math.min(i, responses.length - 1)]
    i += 1
    return route.fulfill(json(body))
  })
}

const NOT_LINKED = { google: { linked: false, email: null, linkedAt: null }, hasPassword: true }
const LINKED_WITH_PASSWORD = {
  google: { linked: true, email: ACCOUNTS.member.email, linkedAt: '2026-08-22T12:34:56' },
  hasPassword: true,
}
const OAUTH_ONLY = {
  google: { linked: true, email: ACCOUNTS.member.email, linkedAt: '2026-08-22T12:34:56' },
  hasPassword: false,
}

test.describe('C5 OAuth 帳號設定', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)
  })

  // ── 顯示狀態 ────────────────────────────────────────────────────────
  test('未綁定：顯示「綁定 Google」按鈕與 Email 相符說明', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.goto('/account/security')
    await expect(page.getByRole('button', { name: '綁定 Google' })).toBeVisible()
    await expect(page.getByText('必須與本站登入 Email 相同')).toBeVisible()
    await expect(page.getByRole('button', { name: '解除綁定' })).toHaveCount(0)
  })

  test('已綁定且有本地密碼：顯示 Google Email、綁定日期、可解除綁定', async ({ page }) => {
    await mockConnections(page, LINKED_WITH_PASSWORD)
    await page.goto('/account/security')
    await expect(page.getByText(ACCOUNTS.member.email).first()).toBeVisible()
    await expect(page.getByText('綁定於 2026/08/22')).toBeVisible()
    await expect(page.getByRole('button', { name: '解除綁定' })).toBeEnabled()
  })

  test('OAuth-only：解除綁定按鈕停用、顯示「設定本地密碼」表單', async ({ page }) => {
    await mockConnections(page, OAUTH_ONLY)
    await page.goto('/account/security')
    await expect(page.getByRole('button', { name: '解除綁定' })).toBeDisabled()
    await expect(page.getByText('請先於下方設定本地密碼後才能解除綁定')).toBeVisible()
    await expect(page.getByRole('heading', { name: '設定本地密碼' })).toBeVisible()
    // 沒有「目前密碼」欄位（這種帳號沒有舊密碼）
    await expect(page.locator('#sec-current-password')).toHaveCount(0)
  })

  // ── 綁定（LINK）────────────────────────────────────────────────────
  test('綁定：輸入密碼 → 取得 authorizationUrl → 整頁導向 Google', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.route(LINK, (route) =>
      route.fulfill(json({ authorizationUrl: '/oauth2/authorization/google?transaction=opaque-1' })),
    )
    let seen = ''
    await page.route('**/oauth2/authorization/google*', (route) => {
      seen = route.request().url()
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'stub' })
    })

    await page.goto('/account/security')
    await page.getByRole('button', { name: '綁定 Google' }).click()
    await page.locator('#link-current-password').fill('Admin@12345')
    await page.getByRole('button', { name: '前往 Google 授權' }).click()

    await expect.poll(() => seen).toContain('/oauth2/authorization/google?transaction=opaque-1')
  })

  test('綁定：後端回非白名單 authorizationUrl → 不導頁、Modal 就地顯示錯誤', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    // pathname 前綴變形（google-evil）——linkAuthorizationUrl() 精確白名單應拒絕
    await page.route(LINK, (route) =>
      route.fulfill(json({ authorizationUrl: '/oauth2/authorization/google-evil?transaction=x' })),
    )
    let navigated = false
    await page.route('**/oauth2/authorization/**', (route) => {
      navigated = true
      return route.fulfill({ status: 200, contentType: 'text/html', body: 'stub' })
    })

    await page.goto('/account/security')
    await page.getByRole('button', { name: '綁定 Google' }).click()
    await page.locator('#link-current-password').fill('Admin@12345')
    await page.getByRole('button', { name: '前往 Google 授權' }).click()

    await expect(page.getByText('無法取得 Google 綁定網址，請稍後再試')).toBeVisible()
    await expect(page).toHaveURL(/\/account\/security/)
    expect(navigated, '非白名單網址不得觸發整頁導向').toBe(false)
  })

  test('綁定：密碼錯誤回 401 REAUTHENTICATION_REQUIRED → Modal 顯示、不登出', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.route(LINK, (route) =>
      route.fulfill(apiError('REAUTHENTICATION_REQUIRED', '目前密碼錯誤', 401)),
    )

    await page.goto('/account/security')
    await page.getByRole('button', { name: '綁定 Google' }).click()
    await page.locator('#link-current-password').fill('wrong-password')
    await page.getByRole('button', { name: '前往 Google 授權' }).click()

    await expect(page.getByText('目前密碼錯誤')).toBeVisible()
    // 仍在頁面上、仍登入（攔截器沒把 REAUTHENTICATION_REQUIRED 當 session 過期）
    await expect(page).toHaveURL(/\/account\/security/)
    expect((await storedAuth(page))?.token).toBeTruthy()
  })

  // ── 解除綁定（UNLINK）─────────────────────────────────────────────
  test('解除綁定：成功 204 → 清登入狀態、導回登入頁', async ({ page }) => {
    await mockConnections(page, LINKED_WITH_PASSWORD)
    await page.route(UNLINK, (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback()
      return route.fulfill({ status: 204, body: '' })
    })

    await page.goto('/account/security')
    await page.getByRole('button', { name: '解除綁定' }).click()
    await page.locator('#unlink-current-password').fill('Admin@12345')
    await page.getByRole('button', { name: '確定解除綁定' }).click()

    await expect(page).toHaveURL(/\/login/)
    expect(await storedAuth(page)).toBeNull()
  })

  test('解除綁定：DELETE 帶 currentPassword 於 request body', async ({ page }) => {
    await mockConnections(page, LINKED_WITH_PASSWORD)
    let bodySeen = null
    await page.route(UNLINK, (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback()
      bodySeen = route.request().postDataJSON()
      return route.fulfill({ status: 204, body: '' })
    })

    await page.goto('/account/security')
    await page.getByRole('button', { name: '解除綁定' }).click()
    await page.locator('#unlink-current-password').fill('Admin@12345')
    await page.getByRole('button', { name: '確定解除綁定' }).click()

    await expect.poll(() => bodySeen).toEqual({ currentPassword: 'Admin@12345' })
  })

  test('解除綁定：密碼錯誤回 401 REAUTHENTICATION_REQUIRED → Modal 顯示、不登出', async ({ page }) => {
    await mockConnections(page, LINKED_WITH_PASSWORD)
    await page.route(UNLINK, (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback()
      return route.fulfill(apiError('REAUTHENTICATION_REQUIRED', '目前密碼錯誤', 401))
    })

    await page.goto('/account/security')
    await page.getByRole('button', { name: '解除綁定' }).click()
    await page.locator('#unlink-current-password').fill('wrong')
    await page.getByRole('button', { name: '確定解除綁定' }).click()

    await expect(page.getByText('目前密碼錯誤')).toBeVisible()
    await expect(page).toHaveURL(/\/account\/security/)
    expect((await storedAuth(page))?.token).toBeTruthy()
  })

  // ── 設定第一組本地密碼（OAuth-only）───────────────────────────────
  test('設定本地密碼：不符規則時送出鈕停用、確認不一致提示', async ({ page }) => {
    await mockConnections(page, OAUTH_ONLY)
    await page.goto('/account/security')

    await page.locator('#sec-new-password').fill('short')
    await expect(page.getByRole('button', { name: '設定本地密碼', exact: true })).toBeDisabled()

    await page.locator('#sec-new-password').fill('Firstpass2026')
    await page.locator('#sec-confirm-password').fill('Different2026')
    await expect(page.getByText('兩次密碼輸入不一致')).toBeVisible()
  })

  test('設定本地密碼：成功 → 換新 token、重查 connections、畫面切成「修改密碼」', async ({ page }) => {
    await mockConnections(page, OAUTH_ONLY, { ...LINKED_WITH_PASSWORD }) // 第二次查詢回 hasPassword:true
    // 成功後 auth.setSession() 會換成假 token，接著 loadAll() 的 getMyAccount() 若打真後端會 401
    // 連帶被登出——這裡把 /api/account/me 也 mock 掉（真實情境 token 是真的，不需要）
    await page.route('**/api/account/me', (route) =>
      route.fulfill(
        json({
          accountId: 1,
          email: ACCOUNTS.member.email,
          roles: ['MEMBER'],
          createdAt: '2026-01-01T00:00:00',
        }),
      ),
    )
    await page.route(SET_PASSWORD, (route) =>
      route.fulfill(
        json({
          accessToken: 'jwt-after-set-password',
          accountId: 1,
          email: ACCOUNTS.member.email,
          roles: ['MEMBER'],
        }),
      ),
    )

    await page.goto('/account/security')
    await page.locator('#sec-new-password').fill('Firstpass2026')
    await page.locator('#sec-confirm-password').fill('Firstpass2026')
    await page.getByRole('button', { name: '設定本地密碼', exact: true }).click()

    // auth store 已換成新 token
    await expect.poll(async () => (await storedAuth(page))?.token).toBe('jwt-after-set-password')
    // 重查後 hasPassword:true → 卡片標題變「修改密碼」、解除綁定按鈕開放
    await expect(page.getByRole('heading', { name: '修改密碼' })).toBeVisible()
    await expect(page.getByRole('button', { name: '解除綁定' })).toBeEnabled()
  })

  test('設定本地密碼：回 409 PASSWORD_ALREADY_CONFIGURED → 就地顯示錯誤', async ({ page }) => {
    await mockConnections(page, OAUTH_ONLY)
    await page.route(SET_PASSWORD, (route) =>
      route.fulfill(apiError('PASSWORD_ALREADY_CONFIGURED', '此帳號已設定本機密碼，如需變更請使用「修改密碼」', 409)),
    )

    await page.goto('/account/security')
    await page.locator('#sec-new-password').fill('Firstpass2026')
    await page.locator('#sec-confirm-password').fill('Firstpass2026')
    await page.getByRole('button', { name: '設定本地密碼', exact: true }).click()

    await expect(page.getByText('此帳號已設定本機密碼，如需變更請使用「修改密碼」')).toBeVisible()
  })

  // ── 綁定 callback（/account/oauth/callback）───────────────────────
  test('callback result=linked：顯示成功通知、清 query、導回帳號安全頁', async ({ page }) => {
    await mockConnections(page, LINKED_WITH_PASSWORD)
    await page.goto('/account/oauth/callback?result=linked')
    await expect(page).toHaveURL(/\/account\/security$/)
    await expect(page.getByText('已成功綁定 Google 帳號。')).toBeVisible()
    expect(page.url()).not.toContain('result=')
  })

  test('callback oauthError=link_email_mismatch：顯示 Email 需相符的說明', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.goto('/account/oauth/callback?oauthError=link_email_mismatch')
    await expect(page).toHaveURL(/\/account\/security$/)
    await expect(page.getByText(/Email 必須與本站帳號相同/)).toBeVisible()
  })

  test('callback：使用者取消（access_denied）不視為系統錯誤', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.goto('/account/oauth/callback?oauthError=access_denied')
    await expect(page.getByText('您已取消 Google 綁定。')).toBeVisible()
  })

  test('callback：未知 oauthError 用通用 fallback 文案', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.goto('/account/oauth/callback?oauthError=some_future_reason')
    await expect(page.getByText('Google 綁定未完成，請再試一次。')).toBeVisible()
  })

  test('callback：同時帶 result=linked 與 oauthError 時一律以失敗處理', async ({ page }) => {
    await mockConnections(page, NOT_LINKED)
    await page.goto('/account/oauth/callback?result=linked&oauthError=identity_in_use')
    await expect(page).toHaveURL(/\/account\/security$/)
    await expect(page.getByText('這個 Google 帳號已被其他帳號綁定，無法重複綁定。')).toBeVisible()
    await expect(page.getByText('已成功綁定 Google 帳號。')).toHaveCount(0)
  })

  // ── RWD ────────────────────────────────────────────────────────────
  test('手機版：登入方式與密碼區塊無水平溢出', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await mockConnections(page, OAUTH_ONLY)
    await page.goto('/account/security')
    await expect(page.getByRole('heading', { name: '設定本地密碼' })).toBeVisible()
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    )
    expect(overflow).toBe(false)
  })
})
