import { test, expect, ACCOUNTS, loginAs, apiToken, ensureCashCoupon } from './fixtures.js'

/**
 * B7 優惠券：領取 → 結帳套用 → 折扣金額正確；無效代碼被擋下。
 *
 * <p>折扣是後端算的（結帳頁只顯示「實際折扣將於送出後計算」），所以斷言看後台訂單明細的
 * subtotalAmount / discountAmount / totalAmount，用數字對數字，不看畫面文字。
 *
 * <p>優惠券用過就不能再用，每跑一輪就永久消耗一張；券由 fixtures 的 ensureCashCoupon 供應
 *（名下未使用 → 可領取 → 管理端新建），所以不必重建資料庫也能一直重跑。
 */

const API = 'http://localhost:8080'
const PRODUCT = '分離乳清蛋白-香草'

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

async function productPrice(request, name) {
  const res = await request.get(`${API}/api/products?keyword=${encodeURIComponent(name)}&size=5`)
  const body = await res.json()
  const hit = (body.items ?? body.content ?? body).find((p) => p.name === name)
  expect(hit, `找不到播種商品 ${name}`).toBeTruthy()
  return Number(hit.price)
}

async function addProductToCart(page, name) {
  await page.goto('/products')
  await expect(page.locator('.item-card').first()).toBeVisible()
  await page.getByPlaceholder('搜尋商品名稱...').fill(name)
  const card = page.locator('.item-card').filter({ hasText: name }).first()
  await expect(card).toBeVisible()
  const added = page.waitForResponse((r) => r.url().includes('/api/cart') && r.request().method() === 'POST')
  await card.getByRole('button', { name: /加入購物車/ }).click()
  await added
}

async function fillReceiver(page) {
  await page.getByPlaceholder('選填').first().fill('E2E 優惠券收件人')
  await page.getByPlaceholder('選填').nth(1).fill('0912345678')
  await page.locator('select').first().selectOption('台北市')
  await page.locator('select').nth(1).selectOption('大安區')
  await page.getByPlaceholder('路名、巷弄、門牌號碼等詳細地址').fill('信義路四段 1 號')
}

/** 送出結帳，回傳建單的 HTTP 回應（成功或失敗都回，由呼叫端斷言） */
async function submitCheckout(page) {
  const orderResponse = page.waitForResponse(
    (r) => r.url().endsWith('/api/orders') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '確認結帳並前往付款' }).click()
  await page.getByRole('button', { name: /確認送出/ }).click()
  return orderResponse
}

test.describe('B7 優惠券結帳', () => {
  test.beforeEach(async ({ page, request }) => {
    await loginAs(page, ACCOUNTS.member)
    await request.delete(`${API}/api/cart`, auth(await apiToken(ACCOUNTS.member)))
    await page.route(/ecpay\.com\.tw/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html', body: 'ECPAY_STUB' }),
    )
  })

  test('套用現金券後訂單折扣與總額正確', async ({ page, request }) => {
    const token = await apiToken(ACCOUNTS.member)
    const price = await productPrice(request, PRODUCT)
    const coupon = await ensureCashCoupon(request, token, price)

    await addProductToCart(page, PRODUCT)
    await page.goto('/checkout')
    await fillReceiver(page)
    await page.getByPlaceholder('輸入優惠券代碼').fill(coupon.code)

    const res = await submitCheckout(page)
    expect(res.ok(), `套券結帳失敗：${await res.text()}`).toBeTruthy()
    const orderId = (await res.json()).orderId

    const adminToken = await apiToken(ACCOUNTS.admin)
    const detail = await (await request.get(`${API}/api/admin/orders/${orderId}`, auth(adminToken))).json()
    expect(Number(detail.subtotalAmount)).toBe(price)
    expect(Number(detail.discountAmount)).toBe(Number(coupon.value))
    expect(Number(detail.totalAmount)).toBe(price - Number(coupon.value))

    // 用掉的券不該再出現在「未使用」清單
    const unused = await (await request.get(`${API}/api/coupons/my-unused`, auth(token))).json()
    const stillThere = (Array.isArray(unused) ? unused : unused.items ?? [])
      .map((x) => x.coupon ?? x)
      .some((c) => c.code === coupon.code)
    expect(stillThere).toBeFalsy()
  })

  test('無效的優惠券代碼被擋下，不建立訂單', async ({ page }) => {
    await addProductToCart(page, PRODUCT)
    await page.goto('/checkout')
    await fillReceiver(page)
    await page.getByPlaceholder('輸入優惠券代碼').fill('E2E-NO-SUCH-CODE')

    const res = await submitCheckout(page)
    expect(res.status()).toBeGreaterThanOrEqual(400)
    // 留在結帳頁、顯示錯誤，而不是導去付款
    await expect(page).toHaveURL(/\/checkout/)
    await expect(page.locator('.form-error, .op-message, [role="alert"]').first()).toBeVisible()
  })

  /**
   * 「一鍵領取全部」要一次領完整份可領清單，不是只領畫面上這一頁——
   * 清單一頁 8 張，改壞的話會退化成「每按一次領一頁」（發生過）。
   * 先由管理端建一張新券保證清單非空，按完斷言清單清空、新券進到名下。
   */
  test('一鍵領取全部：按一次就領完，清單清空', async ({ page, request }) => {
    const adminToken = await apiToken(ACCOUNTS.admin)
    const memberToken = await apiToken(ACCOUNTS.member)
    const code = `E2E${Date.now().toString(36).toUpperCase()}A`
    const iso = (d) => d.toISOString().slice(0, 10)
    const start = new Date()
    start.setDate(start.getDate() - 1)
    const end = new Date()
    end.setFullYear(end.getFullYear() + 1)
    const created = await request.post(`${API}/api/admin/coupons`, {
      ...auth(adminToken),
      data: { code, name: `E2E 一鍵領取 ${code}`, type: 'CASH', value: 30, minSpend: 100,
        totalQuantity: 50, startDate: iso(start), endDate: iso(end), applicableType: 'PRODUCT' },
    })
    expect(created.ok(), `建券失敗：${await created.text()}`).toBeTruthy()
    const couponId = (await created.json()).couponId

    await loginAs(page, ACCOUNTS.member)
    await page.goto('/coupons')
    await page.getByRole('button', { name: /一鍵領取全部/ }).click()
    await expect(page.getByText('目前沒有可領取的優惠券')).toBeVisible()

    const unused = await (await request.get(`${API}/api/coupons/my-unused`, auth(memberToken))).json()
    const got = (Array.isArray(unused) ? unused : unused.items ?? [])
      .map((x) => x.coupon ?? x)
      .some((c) => c.couponId === couponId)
    expect(got, '剛建立的券應出現在名下未使用').toBeTruthy()
  })
})
