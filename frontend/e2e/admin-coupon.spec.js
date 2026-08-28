import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * A10 後台優惠券：建立 → 指定發放（名單含不存在會員 → 整批擋下）→ 正確名單發放 → 群發不重複。
 *
 * <p>指定發放的語意在合併 feat/admin-ui-standardization 時從「跳過有問題的會員」改成
 * 「整批擋下」（EmployeeCouponService.distributeToMembers 在 @Transactional 裡直接拋例外），
 * 之前只有單元測試；這裡從真實 API 驗：有一個不存在的 ID，名單裡正常的會員也不能拿到券。
 *
 * <p>建立走後台頁面表單；發放走 API——後台頁面的「指定發放」彈窗與群發函式存在，
 * 但目前列表上沒有任何按鈕會打開它們（AdminCoupons.vue 只剩編輯／下架），畫面上到不了。
 */

const API = 'http://localhost:8080'
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })
const NON_EXISTENT_MEMBER_ID = 999999

const isoDate = (d) => d.toISOString().slice(0, 10)

async function findMemberId(request, adminToken, email) {
  const body = await (await request.get(`${API}/api/admin/members?keyword=${encodeURIComponent(email)}&size=20`, auth(adminToken))).json()
  const hit = (body.content ?? body.items ?? []).find((m) => m.email === email)
  expect(hit, `後台會員列表找不到 ${email}`).toBeTruthy()
  return hit.memberId
}

async function myUnusedCount(request, memberToken, couponId) {
  const body = await (await request.get(`${API}/api/coupons/my-unused`, auth(memberToken))).json()
  return (Array.isArray(body) ? body : body.items ?? body.content ?? [])
    .map((x) => x.coupon ?? x)
    .filter((c) => c.couponId === couponId).length
}

test.describe('A10 後台優惠券', () => {
  test('後台建立 → 名單含不存在會員整批擋下 → 正確名單發放 → 群發不重複', async ({ page, request }) => {
    const code = `E2E${Date.now().toString(36).toUpperCase()}`
    const adminToken = await apiToken(ACCOUNTS.admin)
    const memberToken = await apiToken(ACCOUNTS.member)
    const memberId = await findMemberId(request, adminToken, ACCOUNTS.member.email)

    // 建立：後台表單
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/coupons')
    await page.getByRole('button', { name: /新增優惠券/ }).click()
    const form = page.locator('form').filter({ hasText: '優惠券代碼' })
    await expect(form).toBeVisible()
    await form.getByPlaceholder('例如: FIT888').fill(code)
    await form.getByPlaceholder('例如: 開幕慶狂歡折抵').fill(`E2E 測試券 ${code}`)
    await form.getByPlaceholder(/輸入金額/).fill('50')
    await form.getByPlaceholder('0 表示不設門檻').fill('100')
    await form.getByPlaceholder('張數').fill('20')
    const start = new Date()
    start.setDate(start.getDate() - 1)
    const end = new Date()
    end.setDate(end.getDate() + 30)
    await form.locator('input[type="date"]').nth(0).fill(isoDate(start))
    await form.locator('input[type="date"]').nth(1).fill(isoDate(end))
    await form.locator('select').nth(1).selectOption('PRODUCT')

    const created = page.waitForResponse(
      (r) => r.url().endsWith('/api/admin/coupons') && r.request().method() === 'POST',
    )
    await form.getByRole('button', { name: '確認發佈發行' }).click()
    const res = await created
    expect(res.ok(), `建立失敗：${await res.text()}`).toBeTruthy()
    const couponId = (await res.json()).couponId
    await expect(page.getByText('優惠券發放成功')).toBeVisible()
    await page.getByPlaceholder('搜尋代碼或名稱...').fill(code)
    await expect(page.locator('tr').filter({ hasText: code })).toContainText('發行中')

    // 指定發放：名單裡有不存在的會員 → 整批擋下，正常的那位也拿不到
    const blocked = await request.post(`${API}/api/admin/coupons/${couponId}/distribute`, {
      ...auth(adminToken), data: [memberId, NON_EXISTENT_MEMBER_ID],
    })
    expect(blocked.status()).toBe(404)
    expect(await blocked.text()).toContain(String(NON_EXISTENT_MEMBER_ID))
    expect(await myUnusedCount(request, memberToken, couponId)).toBe(0)

    // 正確名單 → 會員名下出現
    const ok = await request.post(`${API}/api/admin/coupons/${couponId}/distribute`, {
      ...auth(adminToken), data: [memberId],
    })
    expect(ok.ok(), `發放失敗：${await ok.text()}`).toBeTruthy()
    expect(await myUnusedCount(request, memberToken, couponId)).toBe(1)

    // 同一人再發一次 → 擋下
    const dup = await request.post(`${API}/api/admin/coupons/${couponId}/distribute`, {
      ...auth(adminToken), data: [memberId],
    })
    expect(dup.status()).toBe(409)

    // 群發全體：已擁有的人不會多一張
    const all = await request.post(`${API}/api/admin/coupons/${couponId}/distribute-all`, auth(adminToken))
    expect(all.ok(), `群發失敗：${await all.text()}`).toBeTruthy()
    expect(await myUnusedCount(request, memberToken, couponId)).toBe(1)

    // 會員端「我的優惠券」看得到
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/coupons')
    // 頁面預設在「領取」頁籤，名下的券在第二個頁籤；一頁 8 張，wendy 名下累積的券可能不只一頁，
    // 一頁頁往後翻直到找到
    await page.locator('.tab-btn').nth(1).click()
    const mine = page.locator('.my-coupons')
    await expect(mine.locator('.coupon-code').first()).toBeVisible()
    const target = mine.locator('.coupon-code').filter({ hasText: code })
    for (let i = 0; i < 10 && (await target.count()) === 0; i++) {
      const next = mine.locator('.page-btn', { hasText: '›' })
      if (!(await next.isEnabled())) break
      await next.click()
      await expect(mine.locator('.coupon-code').first()).toBeVisible()
    }
    await expect(target).toHaveCount(1)
  })
})
