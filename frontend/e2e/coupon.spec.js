import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B7 優惠券：領取 → 結帳套用 → 折扣金額正確；無效代碼被擋下。
 *
 * <p>折扣是後端算的（結帳頁只顯示「實際折扣將於送出後計算」），所以斷言看後台訂單明細的
 * subtotalAmount / discountAmount / totalAmount，用數字對數字，不看畫面文字。
 *
 * <p>優惠券用過就不能再用：每輪從「名下未使用」找不到就去「可領取」領一張，種子有
 * 十幾張全館／商品現金券，撐得起多輪；乾淨 DB 靠 `npm run test:e2e:fresh`。
 */

const API = 'http://localhost:8080'
const PRODUCT = '分離乳清蛋白-香草'

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

/** 現金券、適用全館或商品、門檻不超過訂單金額 */
const usableOn = (amount) => (c) =>
  c && c.type === 'CASH' && ['ALL', 'PRODUCT'].includes(c.applicableType) && Number(c.minSpend) <= amount

/** 先看名下未使用的券，沒有就從可領取清單領一張；領取撞到「已擁有」就換下一張 */
async function ensureCashCoupon(request, token, amount) {
  const mine = await (await request.get(`${API}/api/coupons/my-unused`, auth(token))).json()
  const owned = (Array.isArray(mine) ? mine : mine.items ?? mine.content ?? [])
    .map((x) => x.coupon ?? x)
    .find(usableOn(amount))
  if (owned) return owned

  const avail = await (await request.get(`${API}/api/coupons/available?size=100`, auth(token))).json()
  const candidates = (avail.content ?? avail.items ?? avail).filter(usableOn(amount))
  expect(candidates.length, '種子裡應有可套用在商品上的現金券').toBeGreaterThan(0)
  for (const c of candidates) {
    const res = await request.post(`${API}/api/coupons/claim/${c.couponId}`, auth(token))
    if (res.ok()) return c
  }
  throw new Error('可領取清單裡的現金券全部領取失敗（可能都已領過且用掉）')
}

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
})
