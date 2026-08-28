import { test, expect, ACCOUNTS, loginAs, apiToken, uniqueSuffix } from './fixtures.js'

/**
 * A9 管理端私教課：新增 → 前台課程方案出現；改價 → 前台同步；下架 → 前台消失。
 *
 * <p>這條是「後台維護 → 會員看到什麼」的資料流，兩端分別由不同人負責，最容易在合併時
 * 對不上（前台曾把分類寫死成「重量訓練」，後台改分類前台就對不到）。所以斷言不只看
 * 後台列表，一定要跨到前台 /courses 的教練卡片選單裡看到同名、同價。
 *
 * <p>後台表單的欄位沒有 id，靠表單標題鎖定那張卡、再依 placeholder／欄位順序定位。
 * 「指派停用教練被擋」這條規則（TrainerService.assertAvailableForNewBusiness）目前沒有
 * 停用教練可用、也沒有管理端停用教練的端點，先不在這裡驗。
 */

const API = 'http://localhost:8080'
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })
const PRICE = 12345
const NEW_PRICE = 23456

/** 前台教練卡片裡的課程方案選項（<option>，看不見但存在） */
const publicOption = (page, name) => page.locator('option').filter({ hasText: name })

test.describe('A9 管理端私教課', () => {
  test('新增 → 前台出現；後台改價 → 前台同步；下架 → 前台消失', async ({ page, request }) => {
    const name = `E2E私教 ${uniqueSuffix()}`
    const adminToken = await apiToken(ACCOUNTS.admin)
    let courseId = null

    try {
      await loginAs(page, ACCOUNTS.admin)
      await page.goto('/admin/courses')
      // 頁面預設在「團體課程」頁籤，私教課在第二個頁籤
      await page.locator('.tab-btn').filter({ hasText: /私教/ }).click()
      await page.getByRole('button', { name: /新增私教/ }).click()
      // AdminFormCard 的標題在 <form> 外層的卡片上，用卡片鎖定
      const form = page.locator('.add-form-card').filter({ hasText: '新增私教課程資料' })
      await expect(form).toBeVisible()

      await form.getByPlaceholder('例如: 1對1私人教練體驗課').fill(name)
      const trainerSelect = form.locator('select').first()
      await trainerSelect.selectOption({ index: 1 })
      const trainerLabel = (await trainerSelect.locator('option:checked').innerText()).trim()
      const trainerName = trainerLabel.split(' (')[0].trim()
      // 堂數／售價／上限 三個數字欄依序排列
      const numbers = form.locator('input[type="number"]')
      await numbers.nth(0).fill('5')
      await numbers.nth(1).fill(String(PRICE))
      await numbers.nth(2).fill('1')
      await form.locator('select').nth(1).selectOption({ label: '重量訓練' })
      await form.getByPlaceholder(/填寫課程特色/).fill('E2E 自動建立，可刪')

      const created = page.waitForResponse(
        (r) => r.url().endsWith('/api/admin/private-courses') && r.request().method() === 'POST',
      )
      await form.getByRole('button', { name: '確認新增上傳' }).click()
      const res = await created
      expect(res.ok(), `新增失敗：${await res.text()}`).toBeTruthy()
      courseId = (await res.json()).privateCourseId
      await expect(page.getByText('私教課程新增成功')).toBeVisible()

      // 後台列表（分頁，用搜尋縮小）
      await page.getByPlaceholder('搜尋課程名稱、教練姓名...').fill(name)
      const row = page.locator('tr').filter({ hasText: name })
      await expect(row).toBeVisible()
      await expect(row).toContainText(trainerName)
      await expect(row).toContainText('上架中')

      // 前台：該教練的卡片選單裡有這個方案、價格正確
      await page.goto('/courses')
      await expect(page.locator('.coach-card').first()).toBeVisible()
      await expect(publicOption(page, name)).toHaveCount(1)
      expect(await publicOption(page, name).textContent()).toMatch(/12,?345/)

      // 後台改價（重新進頁會回到團體課程頁籤，要再切一次）
      await page.goto('/admin/courses')
      await page.locator('.tab-btn').filter({ hasText: /私教/ }).click()
      await page.getByPlaceholder('搜尋課程名稱、教練姓名...').fill(name)
      await page.locator('tr').filter({ hasText: name }).getByRole('button', { name: '編輯' }).click()
      const modal = page.locator('#editPrivateCourseForm')
      await expect(modal).toBeVisible()
      await expect(modal.locator('input').first()).toHaveValue(name)
      await modal.locator('input[type="number"]').nth(1).fill(String(NEW_PRICE))
      const updated = page.waitForResponse(
        (r) => r.url().endsWith(`/api/admin/private-courses/${courseId}`) && r.request().method() === 'PUT',
      )
      await page.getByRole('button', { name: '保存修改資料' }).click()
      expect((await updated).ok()).toBeTruthy()

      // 前台同步
      await page.goto('/courses')
      await expect(page.locator('.coach-card').first()).toBeVisible()
      expect(await publicOption(page, name).textContent()).toMatch(/23,?456/)
    } finally {
      if (courseId) {
        const off = await request.post(`${API}/api/admin/private-courses/${courseId}/deactivate`, auth(adminToken))
        expect(off.ok(), `收尾下架失敗：${await off.text()}`).toBeTruthy()
      }
    }

    // 下架後前台不再列出
    await page.goto('/courses')
    await expect(page.locator('.coach-card').first()).toBeVisible()
    await expect(publicOption(page, name)).toHaveCount(0)
  })
})
