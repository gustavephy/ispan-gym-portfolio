import { test, expect, loginAs, apiToken, PASSWORD } from './fixtures.js'

/**
 * C4 帳號安全：改密碼後舊 token 立即失效。
 *
 * <p>驗的是授權設計的核心機制——token 只帶 credentials_version 快照，改密碼後版本 +1，
 * 舊 token 下一個請求就被 JwtAuthFilter 視為未登入；本裝置由後端回應換發新 token 維持登入。
 * 目前只有後端單元測試證明這件事，這裡補端到端。
 *
 * <p>用一個較少被其他 spec 碰的播種教練帳號，改完在 finally 一定改回 Admin@12345——
 * 不改回來，fixtures 的登入快取會在下一輪對這個帳號整批失敗。
 *
 * <p>後端不允許重用最近 4 組密碼（PasswordService.PASSWORD_HISTORY_LIMIT），所以：
 * 新密碼每次都帶時間戳保證沒用過；改回播種密碼前要先用 4 組拋棄式密碼把它擠出歷史。
 * 每次改密碼都會回傳新 session，接著用新 token 繼續改，不必重新登入（登入有 IP 限流）。
 */

const API = 'http://localhost:8080'
const ACCOUNT = { email: 'emp-trainer6@local.test', roleLabel: '教練' }
const HISTORY_LIMIT = 4
// 符合後端規則：8–72 字、含大小寫與數字、無空白；帶時間戳避免撞到歷史
const NEW_PASSWORD = `E2e${Date.now()}Pw`

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

async function login(request, email, password) {
  return request.post(`${API}/api/auth/login`, { data: { email, password } })
}

/** 改密碼並回傳換發的新 token */
async function changePassword(request, token, from, to) {
  const res = await request.post(`${API}/api/account/me/change-password`, {
    ...auth(token),
    data: { currentPassword: from, newPassword: to, confirmPassword: to },
  })
  expect(res.ok(), `改密碼失敗：${await res.text()}`).toBeTruthy()
  return (await res.json()).accessToken
}

/** 用 4 組拋棄式密碼把播種密碼擠出歷史，再改回播種密碼 */
async function restoreSeedPassword(request, currentPassword) {
  let token = (await (await login(request, ACCOUNT.email, currentPassword)).json()).accessToken
  let from = currentPassword
  for (let i = 0; i < HISTORY_LIMIT; i++) {
    const next = `Tmp${Date.now()}${i}Pw`
    token = await changePassword(request, token, from, next)
    from = next
  }
  await changePassword(request, token, from, PASSWORD)
  expect((await login(request, ACCOUNT.email, PASSWORD)).ok(), '播種密碼應已還原').toBeTruthy()
}

test.describe('C4 帳號安全', () => {
  test('改密碼後：舊 token 立即 401，本裝置換發新 token，舊密碼登不進', async ({ page, request }) => {
    const oldToken = await apiToken(ACCOUNT)
    expect((await request.get(`${API}/api/account/me`, auth(oldToken))).ok()).toBeTruthy()

    await loginAs(page, ACCOUNT)
    await page.goto('/account/security')

    let changed = false
    try {
      await page.locator('#sec-current-password').fill(PASSWORD)
      await page.locator('#sec-new-password').fill(NEW_PASSWORD)
      await page.locator('#sec-confirm-password').fill(NEW_PASSWORD)
      const res = page.waitForResponse(
        (r) => r.url().endsWith('/api/account/me/change-password') && r.request().method() === 'POST',
      )
      await page.getByRole('button', { name: '修改密碼' }).click()
      expect((await res).ok(), '改密碼應成功').toBeTruthy()
      changed = true
      await expect(page.getByText('密碼已修改')).toBeVisible()

      // 舊 token：版本已過期，受保護端點回 401
      expect((await request.get(`${API}/api/account/me`, auth(oldToken))).status()).toBe(401)

      // 本裝置：頁面已用回應換發新 token，仍是登入狀態且能打 API
      const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('auth') || '{}'))
      expect(stored.token).toBeTruthy()
      expect(stored.token).not.toBe(oldToken)
      expect((await request.get(`${API}/api/account/me`, auth(stored.token))).ok()).toBeTruthy()

      // 密碼本身：舊的登不進、新的登得進
      expect((await login(request, ACCOUNT.email, PASSWORD)).status()).toBe(401)
      expect((await login(request, ACCOUNT.email, NEW_PASSWORD)).ok()).toBeTruthy()
    } finally {
      if (changed) await restoreSeedPassword(request, NEW_PASSWORD)
    }
  })

  test('新密碼不符規則被擋下', async ({ page }) => {
    await loginAs(page, ACCOUNT)
    await page.goto('/account/security')
    await page.locator('#sec-current-password').fill(PASSWORD)
    await page.locator('#sec-new-password').fill('short')
    await page.locator('#sec-confirm-password').fill('short')
    // 不管是前端就地擋下（按鈕停用／不送出）還是後端 400，結果都一樣：密碼不能變
    const submit = page.getByRole('button', { name: '修改密碼' })
    const attempt = page
      .waitForResponse((r) => r.url().endsWith('/api/account/me/change-password'), { timeout: 3000 })
      .catch(() => null)
    if (await submit.isEnabled()) await submit.click()
    const res = await attempt
    if (res) expect(res.ok(), '後端不該接受不符規則的密碼').toBeFalsy()
    const login = await page.request.post(`${API}/api/auth/login`, { data: { email: ACCOUNT.email, password: PASSWORD } })
    expect(login.ok(), '舊密碼應仍可登入').toBeTruthy()
  })
})
