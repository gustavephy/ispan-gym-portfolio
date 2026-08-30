import { test, expect, ACCOUNTS, loginAs, apiToken, registerActivatedMember, ensureActiveSubscription } from './fixtures.js'

/**
 * B6 課程方案頁：私教／團課兩個分頁的篩選，以及私教課直接購買 → 額度發放。
 *
 * <p>篩選案例鎖住 2026-08-28 修掉的兩個 bug：教練下拉在切分頁後只剩「全部教練」、
 * 私教分頁下拉讀到未載入的 store 清單。斷言的是「結果真的縮小」而不只是「下拉有選項」。
 *
 * <p>購買案例走真實流程：建單 → 拿綠界表單（攔下往綠界的導向）→ 後台模擬付款 →
 * 會員額度多出這門課的堂數。訂單留在資料庫（跟 checkout.spec 一樣），乾淨 DB 靠
 * `npm run test:e2e:fresh` 重建。
 */

const API = 'http://localhost:8080'

/** 搜尋列最後一個下拉是「指導教練」（前一個是課程種類） */
const trainerSelect = (page) => page.locator('.csb-select').last()
const categorySelect = (page) => page.locator('.csb-select').first()

test.describe('B6 課程方案篩選', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/courses')
    await expect(page.locator('.coach-card').first()).toBeVisible()
  })

  test('私教分頁：教練下拉有選項，選一位後只剩他的方案卡，下拉本身不縮水', async ({ page }) => {
    const options = trainerSelect(page).locator('option')
    const optionCount = await options.count()
    expect(optionCount, '除了「全部教練」至少要有一位教練').toBeGreaterThan(1)

    const picked = (await options.nth(1).innerText()).trim()
    await trainerSelect(page).selectOption({ index: 1 })

    const cards = page.locator('.coach-card')
    await expect(cards).toHaveCount(1)
    await expect(cards.first()).toContainText(picked)
    // 下拉選項數不變——之前的回歸是選了教練後下拉只剩他一個
    await expect(options).toHaveCount(optionCount)
  })

  test('團課分頁：課程種類與教練篩選都會縮小結果', async ({ page }) => {
    await page.getByRole('button', { name: '團體課程' }).click()
    const cards = page.locator('.product-card-wrap')
    await expect(cards.first()).toBeVisible()
    const all = await cards.count()

    await categorySelect(page).selectOption({ index: 1 })
    await expect.poll(async () => cards.count()).toBeLessThan(all)
    const byCategory = await cards.count()
    expect(byCategory).toBeGreaterThan(0)

    // 恢復種類，改用教練篩選：結果每張卡都要是這位教練
    await categorySelect(page).selectOption({ index: 0 })
    await expect.poll(async () => cards.count()).toBe(all)
    const trainerOptions = trainerSelect(page).locator('option')
    expect(await trainerOptions.count()).toBeGreaterThan(1)
    const picked = (await trainerOptions.nth(1).innerText()).trim()
    await trainerSelect(page).selectOption({ index: 1 })
    await expect.poll(async () => cards.count()).toBeLessThan(all)
    for (const text of await cards.allInnerTexts()) {
      expect(text).toContain(picked)
    }
  })
})

test.describe('B6 私教課購買', () => {
  test('購買方案後模擬付款，會員額度多出該課程的堂數', async ({ page, request }) => {
    // 前台會把「已購且還有剩餘堂數」的方案鎖成不可購買（feat/private-course-improvements），
    // wendy 跑過一輪就買不了第二次；每輪改用全新註冊的會員，順便驗新會員的完整購買路徑
    const { session } = await registerActivatedMember(request, 'e2e-buy')
    // 買私教課要先有生效中的會籍（SUBSCRIPTION_REQUIRED），新會員先訂閱一個方案
    await ensureActiveSubscription(request, session.accessToken)
    await page.goto('/')
    await page.evaluate((s) => {
      localStorage.setItem('auth', JSON.stringify({
        token: s.accessToken, accountId: s.accountId, email: s.email, roles: s.roles,
      }))
    }, session)
    const token = session.accessToken
    const quotasBefore = (await (await request.get(`${API}/api/member/quotas`, {
      headers: { Authorization: `Bearer ${token}` },
    })).json())

    // 攔下往綠界的表單送出，瀏覽器留在站內
    await page.route(/ecpay\.com\.tw/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html', body: 'ECPAY_STUB' }),
    )

    await page.goto('/courses')
    const card = page.locator('.coach-card').first()
    await expect(card).toBeVisible()
    const select = card.locator('select')
    const selectedOption = select.locator('option:checked')
    // 選項文字格式：「課程名 - NT$ 價格」，課程名用來對照額度
    const optionText = (await selectedOption.innerText()).trim()
    const courseName = optionText.split(' - NT$')[0].trim()
    const sessionsText = await card.locator('.selected-course-details').innerText()
    const sessionPerUnit = Number((sessionsText.match(/共\s*(\d+)\s*堂/) || [])[1])
    expect(sessionPerUnit, '方案卡應顯示堂數').toBeGreaterThan(0)

    const orderResponse = page.waitForResponse(
      (r) => r.url().endsWith('/api/orders/direct') && r.request().method() === 'POST',
    )
    await card.getByRole('button', { name: '確認購買課程' }).click()
    const orderRes = await orderResponse
    expect(orderRes.ok(), `建單失敗：${await orderRes.text()}`).toBeTruthy()
    const orderId = (await orderRes.json()).orderId
    expect(orderId).toBeTruthy()

    // 繞過綠界：後台模擬付款，走與真實付款成功相同的履約路徑
    const adminToken = await apiToken(ACCOUNTS.admin)
    const paid = await request.post(`${API}/api/admin/orders/${orderId}/simulate-payment`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(paid.ok(), `模擬付款失敗：${await paid.text()}`).toBeTruthy()
    expect((await paid.json()).status).toBe('PAID')

    // 額度：這門課的剩餘堂數總和要比購買前多出 sessionPerUnit
    const remainingOf = (quotas) =>
      quotas.filter((q) => q.courseName === courseName).reduce((s, q) => s + (q.remainingCourse ?? 0), 0)
    const quotasAfter = (await (await request.get(`${API}/api/member/quotas`, {
      headers: { Authorization: `Bearer ${token}` },
    })).json())
    expect(remainingOf(quotasAfter)).toBe(remainingOf(quotasBefore) + sessionPerUnit)

    // 前台「我的課程」也看得到這門課的額度卡
    await page.goto('/member/courses')
    await expect(page.locator('.quota-card, [class*=quota]').filter({ hasText: courseName }).first()).toBeVisible()
  })
})
