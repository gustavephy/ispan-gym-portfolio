import { test, expect, ACCOUNTS, loginAs, apiToken, uniqueSuffix, pickInStockProduct } from './fixtures.js'

/**
 * C5 會員資料修改：改電話／地址 → 重新整理仍在 → 切頁再回來仍是新值 → 結帳頁收件資訊預設帶入。
 *
 * <p>會員資料被三個地方讀：資料頁本身、keep-alive 快取的頁面（切走再回來要重抓，
 * 這是最近補的 onActivated），還有結帳頁的「使用會員資料地址」——最後這個是資料一致性
 * 最容易漏的地方，改了資料但結帳還帶舊地址就會寄錯。
 *
 * <p>對象是播種的 wendy；finally 一定把資料改回原值，別的 spec 靠她的名字比對。
 */

const API = 'http://localhost:8080'
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

const UPDATE_FIELDS = ['firstName', 'lastName', 'phone', 'dob', 'gender', 'emergencyContact', 'emergencyPhone', 'zipCode', 'city', 'district', 'address']

test.describe('C5 會員資料', () => {
  test('改電話與地址後：重整仍在、切頁回來仍在、結帳頁預設帶入', async ({ page, request }) => {
    const token = await apiToken(ACCOUNTS.member)
    const original = await (await request.get(`${API}/api/member/profile`, auth(token))).json()
    const digits = uniqueSuffix().replace(/\D/g, '').slice(-6)
    const phone = `0988${digits.padStart(6, '0')}`
    const street = `測試路 ${digits} 號`

    try {
      await loginAs(page, ACCOUNTS.member)
      await page.goto('/member/profile')
      await expect(page.locator('#mp-phone')).toHaveValue(original.phone ?? '')

      await page.locator('#mp-phone').fill(phone)
      await page.locator('#mp-city').fill('台北市')
      await page.locator('#mp-district').fill('信義區')
      await page.locator('#mp-address').fill(street)
      const saved = page.waitForResponse(
        (r) => r.url().endsWith('/api/member/profile') && r.request().method() === 'PUT',
      )
      // 儲存前有二次確認彈窗，列出變更欄位，按「確認修改」才真的送出
      await page.locator('form button[type="submit"]').first().click()
      await expect(page.getByRole('heading', { name: '確認修改會員資料' })).toBeVisible()
      await expect(page.getByText('鄉鎮市區', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: '確認修改' }).click()
      const res = await saved
      expect(res.ok(), `儲存失敗：${await res.text()}`).toBeTruthy()
      await expect(page.getByText('會員資料已更新')).toBeVisible()

      // 重新整理：資料真的存進去了
      await page.reload()
      await expect(page.locator('#mp-phone')).toHaveValue(phone)
      await expect(page.locator('#mp-address')).toHaveValue(street)

      // 切到別頁再回來（keep-alive 頁面）仍是新值
      await page.goto('/orders')
      await page.goto('/member/profile')
      await expect(page.locator('#mp-phone')).toHaveValue(phone)

      // 結帳頁：預設就選「使用會員資料地址」，內容是剛改的
      await request.delete(`${API}/api/cart`, auth(token))
      const add = await request.post(`${API}/api/cart/items`, {
        ...auth(token), data: { productId: (await pickInStockProduct(request)).productId, quantity: 1 },
      })
      expect(add.ok()).toBeTruthy()
      await page.goto('/checkout')
      const profileOption = page.getByRole('button', { name: /使用會員資料地址/ })
      await expect(profileOption).toBeVisible()
      await expect(profileOption).toHaveAttribute('aria-pressed', 'true')
      await expect(profileOption).toContainText(phone)
      await expect(profileOption).toContainText(`台北市信義區${street}`)
    } finally {
      const restore = await request.put(`${API}/api/member/profile`, {
        ...auth(token),
        data: Object.fromEntries(UPDATE_FIELDS.map((k) => [k, original[k] ?? null])),
      })
      expect(restore.ok(), `資料改回原值失敗：${await restore.text()}`).toBeTruthy()
    }
  })

  test('電話格式錯誤被擋下，資料不變', async ({ page, request }) => {
    const token = await apiToken(ACCOUNTS.member)
    const before = (await (await request.get(`${API}/api/member/profile`, auth(token))).json()).phone
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/member/profile')
    await page.locator('#mp-phone').fill('12345')
    const attempt = page
      .waitForResponse((r) => r.url().endsWith('/api/member/profile') && r.request().method() === 'PUT', { timeout: 3000 })
      .catch(() => null)
    await page.locator('form button[type="submit"]').first().click()
    // 格式錯誤時前端擋在確認彈窗之前，彈窗根本不該出現
    await expect(page.getByRole('heading', { name: '確認修改會員資料' })).toHaveCount(0)
    const res = await attempt
    if (res) expect(res.ok(), '後端不該接受格式錯誤的電話').toBeFalsy()
    expect((await (await request.get(`${API}/api/member/profile`, auth(token))).json()).phone).toBe(before)
  })
})
