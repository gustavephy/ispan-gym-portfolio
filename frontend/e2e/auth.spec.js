import { test, expect, ACCOUNTS, loginAs, loginViaForm } from './fixtures.js'

/**
 * 導覽列上顯示「目前工作區」的徽章。它同時是身分下拉選單的觸發器，
 * 多工作區時算繪成 button、單一工作區時是 span，所以用 class 定位而不是 role。
 *
 * 徽章顯示的是工作區（管理／教練／會員）而非角色——emp-admin 同時具備
 * MEMBER/EMPLOYEE/ADMIN，徽章只顯示當下所在的那一個，完整角色清單在 title。
 */
function workspaceBadge(page) {
  return page.locator('.nav-auth__role')
}

/** 展開身分下拉選單，回傳選單本身以便斷言可切換的工作區 */
async function openWorkspaceMenu(page) {
  await workspaceBadge(page).click()
  return page.getByRole('menu', { name: '切換身分' })
}

test.describe('A2 登入與角色導流', () => {
  test('會員登入後導覽列顯示身分', async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)

    await expect(page).toHaveURL('/')
    await expect(workspaceBadge(page)).toHaveText(ACCOUNTS.member.roleLabel)
  })

  test('管理員登入後看得到管理工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)

    // 徽章本身顯示工作區名稱「管理」，完整角色（含「管理員」）收在 title
    await expect(workspaceBadge(page)).toHaveAttribute('title', new RegExp(ACCOUNTS.admin.roleLabel))

    const menu = await openWorkspaceMenu(page)
    await expect(menu.getByRole('menuitemradio', { name: '管理' })).toBeVisible()
  })

  test('教練登入後看得到教練工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)

    await expect(workspaceBadge(page)).toHaveAttribute('title', new RegExp(ACCOUNTS.trainer.roleLabel))

    const menu = await openWorkspaceMenu(page)
    await expect(menu.getByRole('menuitemradio', { name: '教練' })).toBeVisible()
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
