import { test, expect, PASSWORD, loginViaForm, uniqueSuffix, sql } from './fixtures.js'

/**
 * C6 註冊 → Email 驗證（前端頁）→ 登入。
 *
 * <p>subscription.spec 用 API 走過 verify-email 端點；這裡驗的是使用者真正看到的頁面：
 * 註冊成功畫面、/verify-email?token= 的成功／失敗文案、失效後在同一頁重寄驗證信。
 * 驗證 token 只存在信件與資料庫（本機收不到信），直接讀 DB 取；「過期」則把
 * expires_at 改成昨天來製造。
 */

const API = 'http://localhost:8080'

function readVerificationToken(email) {
  return sql(`SET NOCOUNT ON; SELECT TOP 1 CAST(v.token AS VARCHAR(40))
    FROM email_verification v JOIN account a ON a.account_id = v.account_id
    WHERE a.email = '${email}' AND v.verified_at IS NULL ORDER BY v.id DESC;`)
}

async function registerByApi(request, email) {
  const res = await request.post(`${API}/api/members/register`, {
    data: { email, password: PASSWORD, lastName: '驗證', firstName: '測試', phone: `09${String(Date.now()).slice(-8)}`, dob: '1995-05-05' },
  })
  expect(res.ok(), `註冊失敗：${await res.text()}`).toBeTruthy()
}

test.describe('C6 Email 驗證', () => {
  test('註冊 → 驗證頁顯示成功 → 可登入', async ({ page }) => {
    const email = `e2e-verify-${uniqueSuffix()}@local.test`

    await page.goto('/member-register')
    await page.locator('#reg-email').fill(email)
    await page.locator('#reg-lastName').fill('驗證')
    await page.locator('#reg-firstName').fill('測試')
    await page.locator('#reg-phone').fill(`09${String(Date.now()).slice(-8)}`)
    await page.locator('#reg-password').fill(PASSWORD)
    await page.locator('#reg-confirm').fill(PASSWORD)
    await page.getByRole('button', { name: '註冊', exact: true }).click()
    // 送出前有確認 Email 地址的彈窗
    await expect(page.getByText('確認 Email 地址')).toBeVisible()
    const registered = page.waitForResponse(
      (r) => r.url().endsWith('/api/members/register') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '確認送出' }).click()
    expect((await registered).ok()).toBeTruthy()
    await expect(page.getByRole('heading', { name: '註冊成功' })).toBeVisible()
    await expect(page.getByText(`我們已寄出驗證信到 ${email}`)).toBeVisible()

    // 未驗證前登不進去
    await loginViaForm(page, { email })
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByTitle(email)).toHaveCount(0)

    const token = readVerificationToken(email)
    expect(token, '取不到驗證 token').toBeTruthy()
    await page.goto(`/verify-email?token=${token}`)
    await expect(page.getByRole('heading', { name: '驗證成功' })).toBeVisible()
    await page.getByRole('link', { name: '前往登入' }).click()
    await loginViaForm(page, { email })
    await expect(page.getByTitle(email)).toBeVisible()
  })

  test('過期連結顯示失敗並可重寄；新連結可驗證', async ({ page, request }) => {
    const email = `e2e-expired-${uniqueSuffix()}@local.test`
    await registerByApi(request, email)
    const token = readVerificationToken(email)
    expect(token).toBeTruthy()
    // 效期改成昨天製造「已過期」；created_at 也一起往前撥——重寄有 1 分鐘冷卻
    //（看最新一筆的 created_at，冷卻中會靜默不重寄），不撥的話註冊後馬上重寄等於沒動作
    sql(`UPDATE email_verification
         SET expires_at = DATEADD(day, -1, GETDATE()), created_at = DATEADD(day, -1, GETDATE())
         WHERE token = '${token}';`)

    await page.goto(`/verify-email?token=${token}`)
    await expect(page.getByRole('heading', { name: '驗證失敗' })).toBeVisible()
    await expect(page.getByText(/已過期/)).toBeVisible()

    // 同一頁重寄
    await page.locator('#resend-email').fill(email)
    const resent = page.waitForResponse((r) => /resend/.test(r.url()) && r.request().method() === 'POST')
    await page.locator('form.resend-form button[type="submit"]').click()
    expect((await resent).ok()).toBeTruthy()

    // 重寄可能換新 token，也可能沿用同一列只延長效期；不管哪種，重寄後的連結都必須能驗證
    const fresh = readVerificationToken(email)
    expect(fresh).toBeTruthy()
    await page.goto(`/verify-email?token=${fresh}`)
    await expect(page.getByRole('heading', { name: '驗證成功' })).toBeVisible()
  })

  test('亂填的 token 顯示失敗', async ({ page }) => {
    await page.goto('/verify-email?token=00000000-0000-0000-0000-000000000000')
    await expect(page.getByRole('heading', { name: '驗證失敗' })).toBeVisible()
  })
})
