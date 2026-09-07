import { createHash, randomBytes } from 'node:crypto'
import { test, expect, PASSWORD, restoreSeedPassword, sql } from './fixtures.js'

/**
 * C7 忘記密碼 → 重設密碼（前端頁）。
 *
 * <p>重設連結只寄到信箱、資料庫只存 token 的 SHA-256（PasswordService），沒有任何 API
 * 能拿到原始 token。測試的做法：先讓後端正常產生一筆申請，再把那筆的 token_hash
 * 換成「我們自己產生的 token」的雜湊——雜湊方式與後端一致（SHA-256 hex），
 * 之後就能拿自己的 token 走真實的重設頁。用完的 token 不能重用、舊密碼失效、新密碼可登入。
 *
 * <p>對象用播種教練帳號 emp-trainer7；重設後密碼歷史規則一樣適用，收尾靠 fixtures 的
 * restoreSeedPassword 改回 Admin@12345。
 */

const API = 'http://localhost:8080'
const EMAIL = 'emp-trainer7@local.test'
const NEW_PASSWORD = `Rst${Date.now()}Pw`

const sha256Hex = (s) => createHash('sha256').update(s, 'utf8').digest('hex')

/** 把該帳號最新一筆未使用的重設申請換成我們自己的 token，回傳原始 token；改不到回 null */
function hijackLatestResetToken(email) {
  const raw = randomBytes(32).toString('base64url')
  // sqlcmd 的「N 個資料列受影響」訊息會隨語系變動，改用 @@ROWCOUNT 回傳數字
  const out = sql(`SET NOCOUNT ON; UPDATE t SET token_hash = '${sha256Hex(raw)}'
    FROM password_reset_token t JOIN account a ON a.account_id = t.account_id
    WHERE a.email = '${email}' AND t.used_at IS NULL AND t.invalidated_at IS NULL
      AND t.password_reset_token_id = (SELECT MAX(x.password_reset_token_id) FROM password_reset_token x WHERE x.account_id = t.account_id);
    SELECT @@ROWCOUNT;`)
  return out === '1' ? raw : null
}

/**
 * 申請重設並攔下 token。forgot-password 有 60 秒 email 冷卻：冷卻內的申請為了防帳號列舉
 * 一樣回 202，但靜默不產生新 token（同一小時內跑過本 spec、或有人剛幫這個帳號申請過就會遇到）。
 * 攔不到就等冷卻過再申請一次。
 */
async function requestAndHijackToken(request, email) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await request.post(`${API}/api/auth/forgot-password`, { data: { email } })
    expect(res.ok()).toBeTruthy()
    const raw = hijackLatestResetToken(email)
    if (raw) return raw
    await new Promise((r) => setTimeout(r, 61_000))
  }
  throw new Error('等過冷卻仍攔不到重設 token')
}

async function login(request, email, password) {
  return request.post(`${API}/api/auth/login`, { data: { email, password } })
}

test.describe('C7 重設密碼', () => {
  test('申請 → 另一分頁重設 → 原分頁同步「密碼已重設」、舊密碼失效、新密碼可登入、連結不能重用', async ({
    page,
    context,
    request,
  }) => {
    test.setTimeout(180_000) // 撞到 forgot-password 的 60 秒冷卻時要原地等一輪，預設 60 秒不夠
    await page.goto('/forgot-password')
    await page.locator('#forgot-email').fill(EMAIL)
    const requested = page.waitForResponse(
      (r) => r.url().endsWith('/api/auth/forgot-password') && r.request().method() === 'POST',
    )
    await page.locator('form button[type="submit"]').first().click()
    expect((await requested).ok()).toBeTruthy()
    // 原分頁留在「已送出」狀態（表單還在、重寄倒數中），等另一分頁完成後靠 BroadcastChannel 同步
    await expect(page.getByText('若此 Email 可使用密碼登入')).toBeVisible()

    // 頁面申請的那筆可能因 60 秒冷卻沒產生 token（見 requestAndHijackToken），helper 會自行補申請
    const raw = hijackLatestResetToken(EMAIL) ?? (await requestAndHijackToken(request, EMAIL))
    let changed = false
    const tabB = await context.newPage()
    try {
      // 另一分頁：從信件連結開啟重設頁並完成重設
      await tabB.goto(`/reset-password?token=${raw}`)
      await tabB.locator('#reset-password').fill(NEW_PASSWORD)
      await tabB.locator('#reset-confirm').fill(NEW_PASSWORD)
      const reset = tabB.waitForResponse(
        (r) => r.url().endsWith('/api/auth/reset-password') && r.request().method() === 'POST',
      )
      await tabB.locator('form button[type="submit"]').first().click()
      const res = await reset
      expect(res.ok(), `重設失敗：${await res.text()}`).toBeTruthy()
      changed = true
      await expect(tabB.getByText('密碼已成功重設')).toBeVisible()

      // 原「忘記密碼」分頁：收到同一個 Email 的完成事件，切成「密碼已重設」
      await expect(page.getByRole('heading', { name: '密碼已重設' })).toBeVisible()

      expect((await login(request, EMAIL, PASSWORD)).status()).toBe(401)
      expect((await login(request, EMAIL, NEW_PASSWORD)).ok()).toBeTruthy()

      // 同一個連結再用一次：已使用，擋下
      const reuse = await request.post(`${API}/api/auth/reset-password`, {
        data: { token: raw, newPassword: `${NEW_PASSWORD}x`, confirmPassword: `${NEW_PASSWORD}x` },
      })
      expect(reuse.ok()).toBeFalsy()
      expect((await login(request, EMAIL, NEW_PASSWORD)).ok()).toBeTruthy()
    } finally {
      await tabB.close()
      if (changed) await restoreSeedPassword(request, EMAIL, NEW_PASSWORD)
    }
  })

  test('沒有 token 進重設頁顯示連結無效；亂填 token 送出被擋', async ({ page }) => {
    await page.goto('/reset-password')
    await expect(page.getByRole('heading', { name: '連結無效' })).toBeVisible()

    await page.goto('/reset-password?token=not-a-real-token')
    await page.locator('#reset-password').fill(NEW_PASSWORD)
    await page.locator('#reset-confirm').fill(NEW_PASSWORD)
    const reset = page.waitForResponse(
      (r) => r.url().endsWith('/api/auth/reset-password') && r.request().method() === 'POST',
    )
    await page.locator('form button[type="submit"]').first().click()
    expect((await reset).ok()).toBeFalsy()
    await expect(page.getByText(/無效或已過期/)).toBeVisible()
  })

  test('不存在的 Email 申請也回成功（不洩漏帳號是否存在）', async ({ request }) => {
    const res = await request.post(`${API}/api/auth/forgot-password`, { data: { email: `nobody-${Date.now()}@local.test` } })
    expect(res.ok()).toBeTruthy()
  })
})
