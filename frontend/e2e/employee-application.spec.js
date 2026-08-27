import { test, expect, ACCOUNTS, PASSWORD, loginAs, loginViaForm, uniqueSuffix } from './fixtures.js'

/**
 * A4 員工申請閉環：申請 → 審核 → 看到結果 → 重新申請。
 *
 * <p>把最近合併的三個 commit 串起來測——申請頁與重新申請頁（6bb7c1d）、
 * 拒絕原因併進登入錯誤訊息（9ad37f3）、審核頁本身。
 *
 * <p>每輪會建立真實的員工帳號，email 帶時間戳避免重複跑撞名。這些帳號無法刪除
 *（後端沒有開放刪除端點），會累積在資料庫裡，都以 e2e- 開頭方便辨識。
 */

/** 送出員工申請，回傳這次用的 email */
async function submitApplication(page, { email, lastName = '測試', firstName = '員工' }) {
  await page.goto('/employee-register')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('姓氏').fill(lastName)
  await page.getByLabel('名字').fill(firstName)
  await page.getByLabel('聯絡電話').fill('0987654321')
  await page.getByLabel('到職日').fill('2026-09-01')
  await page.getByLabel('密碼', { exact: true }).fill(PASSWORD)
  await page.getByLabel('確認密碼').fill(PASSWORD)

  const submitted = page.waitForResponse(
    (r) => r.url().includes('/api/employees/register') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '送出申請' }).click()
  await submitted
}

/** 審核卡片：用 email 定位，姓名可能重複 */
function reviewCard(page, email) {
  return page.locator('.review-card').filter({ hasText: email }).first()
}

test.describe('A4 員工申請與審核', () => {
  test('送出申請後出現在管理者的待審核清單', async ({ page }) => {
    const email = `e2e-apply-${uniqueSuffix()}@local.test`

    await submitApplication(page, { email })
    await expect(page.getByText('申請已送出')).toBeVisible()

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employee-reviews')
    await expect(reviewCard(page, email)).toBeVisible()
  })

  test('核准後申請人可以登入', async ({ page }) => {
    const email = `e2e-approve-${uniqueSuffix()}@local.test`
    await submitApplication(page, { email })

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employee-reviews')

    const approved = page.waitForResponse(
      (r) => r.url().includes('/approve') && r.request().method() === 'POST',
    )
    await reviewCard(page, email).getByRole('button', { name: '核准' }).click()
    await page.getByRole('button', { name: '確定核准' }).click()
    await approved

    // 核准後就從待審核清單消失
    await expect(reviewCard(page, email)).toHaveCount(0)

    // 申請人現在登得進去了
    await page.context().clearCookies()
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())
    await loginViaForm(page, { email })
    await expect(page.getByTitle(email)).toBeVisible()
  })

  /**
   * 拒絕原因要一路傳到申請人的登入錯誤訊息，讓他知道為什麼被拒、下一步怎麼做。
   * 這是 9ad37f3 的重點：不新增 API 欄位、不改前端，直接併進既有的 401 訊息。
   */
  test('拒絕後申請人登入時看得到拒絕原因，並可重新申請', async ({ page }) => {
    const email = `e2e-reject-${uniqueSuffix()}@local.test`
    const reason = '檢附文件不完整，請補齊後再申請'
    await submitApplication(page, { email })

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employee-reviews')

    const rejected = page.waitForResponse(
      (r) => r.url().includes('/reject') && r.request().method() === 'POST',
    )
    await reviewCard(page, email).getByRole('button', { name: '拒絕' }).click()
    await page.getByRole('textbox').fill(reason)
    await page.getByRole('button', { name: '確定拒絕' }).click()
    await rejected

    await expect(reviewCard(page, email)).toHaveCount(0)

    // 被拒絕的帳號登入時，錯誤訊息應該帶出審核者填的原因
    await page.context().clearCookies()
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())
    await loginViaForm(page, { email })
    await expect(page.getByText(new RegExp(reason))).toBeVisible()

    // 重新申請後回到待審核狀態
    await page.goto('/employee-reapply')
    await page.getByLabel('Email').fill(email)
    await page.getByLabel('密碼').fill(PASSWORD)
    await page.getByLabel('姓氏').fill('測試')
    await page.getByLabel('名字').fill('員工')
    await page.getByLabel('聯絡電話').fill('0987654321')
    await page.getByLabel('到職日').fill('2026-09-01')

    const reapplied = page.waitForResponse(
      (r) => r.url().includes('/api/employees/reapply') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: /送出/ }).click()
    await reapplied

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employee-reviews')
    await expect(reviewCard(page, email)).toBeVisible()
  })

  test('拒絕時沒填原因會被擋下', async ({ page }) => {
    const email = `e2e-noreason-${uniqueSuffix()}@local.test`
    await submitApplication(page, { email })

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employee-reviews')
    await reviewCard(page, email).getByRole('button', { name: '拒絕' }).click()
    await page.getByRole('button', { name: '確定拒絕' }).click()

    await expect(page.getByText('請填寫拒絕原因')).toBeVisible()
  })
})
