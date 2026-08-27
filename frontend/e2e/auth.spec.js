import { test, expect, ACCOUNTS, loginAs, loginViaForm } from './fixtures.js'

/**
 * 身分徽章。不能直接用 getByText 找「教練」「管理員」——導覽列的工作區切換頁籤
 * 用的是同一批字，會同時命中兩個元素。徽章清單有 aria-label="目前身分"，
 * 從那裡進去才是唯一的。
 */
function roleBadge(page, label) {
  return page.getByLabel('目前身分').getByText(label, { exact: true })
}

test.describe('A2 登入與角色導流', () => {
  test('會員登入後導覽列顯示身分', async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)

    await expect(page).toHaveURL('/')
    await expect(roleBadge(page, ACCOUNTS.member.roleLabel)).toBeVisible()
  })

  test('管理員登入後看得到管理工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)

    await expect(roleBadge(page, ACCOUNTS.admin.roleLabel)).toBeVisible()
    await expect(page.getByRole('tab', { name: '管理' })).toBeVisible()
  })

  test('教練登入後看得到教練工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)

    await expect(roleBadge(page, ACCOUNTS.trainer.roleLabel)).toBeVisible()
    await expect(page.getByRole('tab', { name: '教練' })).toBeVisible()
  })

  /**
   * 回歸測試：純管理員（沒有 MEMBER 身分）登入後不該被踢回登入頁。
   *
   * 首頁／課程頁／會籍頁都會查會員訂閱狀態，後端對非會員回 401，
   * 而攔截器把 401 一律當成 session 過期就會清 token 並導向 /login。
   * 現在守衛收在 api/subscription.js 的 getMySubscription() 裡，
   * 這三頁都不該再發出那個請求。
   */
  test('純管理員逛完首頁、課程、會籍頁都不會被登出', async ({ page }) => {
    await loginAs(page, ACCOUNTS.adminOnly)

    for (const path of ['/', '/courses', '/subscription']) {
      await page.goto(path)
      await expect(page).toHaveURL(path)
      await expect(page.getByTitle(ACCOUNTS.adminOnly.email)).toBeVisible()
    }
  })

  /**
   * 唯一真的走登入表單的案例——其餘測試都用 loginAs() 直接注入 session，
   * 因為後端對登入有限流（同 IP 每分鐘 10 次），每個測試各自登入很快就會 429。
   */
  test('從登入表單登入成功後回到首頁', async ({ page }) => {
    await loginViaForm(page, ACCOUNTS.member)

    await expect(page).toHaveURL('/')
    await expect(page.getByTitle(ACCOUNTS.member.email)).toBeVisible()
  })

  test('密碼錯誤時留在登入頁並顯示訊息', async ({ page }) => {
    await loginViaForm(page, ACCOUNTS.member, 'WrongPassword@999')

    await expect(page.getByText('帳號或密碼錯誤')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('未登入直接開後台會被導向登入頁', async ({ page }) => {
    await page.goto('/admin/orders')

    await expect(page).toHaveURL(/\/login/)
  })

  /** 舊書籤不該變成 404：/register 應重新導向到 /member-register */
  test('舊的 /register 路徑仍可用', async ({ page }) => {
    await page.goto('/register')

    await expect(page).toHaveURL('/member-register')
  })
})
