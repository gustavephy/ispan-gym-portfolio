import { test, expect, ACCOUNTS, loginAs, loginViaForm } from './fixtures.js'

/**
 * 導覽列已登入區塊有兩處會用到同一批字（會員／教練／管理），定位時要分開：
 *
 * - 角色徽章：`<ul aria-label="目前身分">` 裡化簡後的對外身分（roleLabels）。
 *   顯示簡稱（ADMIN → 「管理」），完整角色（含「管理員」）收在這個 <ul> 的 title。
 * - 工作區切換頁籤：`<div role="tablist" aria-label="工作區">`，多於一個工作區才算繪，
 *   點了會切換目前顯示的導覽連結組。
 */
function roleList(page) {
  return page.getByLabel('目前身分')
}

function roleBadge(page, label) {
  return roleList(page).getByText(label, { exact: true })
}

function workspaceTab(page, name) {
  return page.getByRole('tablist', { name: '工作區' }).getByRole('tab', { name })
}

test.describe('A2 登入與角色導流', () => {
  test('會員登入後導覽列顯示身分', async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)

    await expect(page).toHaveURL('/')
    await expect(roleBadge(page, ACCOUNTS.member.roleLabel)).toBeVisible()
    // 純會員只有一個工作區，不算繪切換頁籤
    await expect(page.getByRole('tablist', { name: '工作區' })).toHaveCount(0)
  })

  test('管理員登入後看得到管理工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)

    // 徽章顯示簡稱「管理」；完整角色（含「管理員」）收在清單的 title
    await expect(roleBadge(page, '管理')).toBeVisible()
    await expect(roleList(page)).toHaveAttribute('title', new RegExp(ACCOUNTS.admin.roleLabel))

    // 預設停在「會員」工作區，點「管理」頁籤後要真的切過去
    const adminTab = workspaceTab(page, '管理')
    await adminTab.click()
    await expect(adminTab).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('link', { name: '會員管理' })).toBeVisible()
  })

  test('教練登入後看得到教練工作區', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)

    await expect(roleBadge(page, ACCOUNTS.trainer.roleLabel)).toBeVisible()
    await expect(roleList(page)).toHaveAttribute('title', new RegExp(ACCOUNTS.trainer.roleLabel))

    const trainerTab = workspaceTab(page, '教練')
    await trainerTab.click()
    await expect(trainerTab).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('link', { name: '教練排班' })).toBeVisible()
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
