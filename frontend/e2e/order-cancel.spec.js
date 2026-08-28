import { test, expect, ACCOUNTS, loginAs, apiToken, ensureCashCoupon, pickInStockProduct } from './fixtures.js'

/**
 * B7 會員取消未付款訂單。
 *
 * <p>庫存在建單當下就扣掉（不是付款時），優惠券也在建單時鎖定；所以取消未付款訂單
 * 必須把兩者一起還回去，不然商品會「假缺貨」、券會憑空消失。這裡從 API 建單、
 * 用「我的訂單」頁面按取消，再回 API 對庫存與券。
 */

const API = 'http://localhost:8080'
const RECEIVER = { receiverName: '測試收件人', receiverPhone: '0912345678', receiverAddress: '台北市測試路 1 號' }
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

async function findProduct(request, name) {
  const res = await request.get(`${API}/api/products?keyword=${encodeURIComponent(name)}&size=5`)
  const body = await res.json()
  const hit = (body.items ?? body.content ?? body).find((p) => p.name === name)
  expect(hit, `找不到播種商品 ${name}`).toBeTruthy()
  return hit
}

async function myUnusedCoupons(request, token) {
  const mine = await (await request.get(`${API}/api/coupons/my-unused`, auth(token))).json()
  return (Array.isArray(mine) ? mine : mine.items ?? mine.content ?? []).map((x) => x.coupon ?? x)
}

test.describe('B7 取消未付款訂單', () => {
  test('取消後庫存回補、優惠券退回，頁面狀態變已取消', async ({ page, request }) => {
    const memberToken = await apiToken(ACCOUNTS.member)
    await request.delete(`${API}/api/cart`, auth(memberToken))

    const product = await pickInStockProduct(request)
    const PRODUCT = product.name
    const stockBefore = product.stock
    const coupon = await ensureCashCoupon(request, memberToken, Number(product.price))
    const couponCode = coupon.code ?? coupon.couponCode
    expect(couponCode).toBeTruthy()

    const add = await request.post(`${API}/api/cart/items`, {
      ...auth(memberToken), data: { productId: product.productId, quantity: 1 },
    })
    expect(add.ok(), `加入購物車失敗：${await add.text()}`).toBeTruthy()
    const created = await request.post(`${API}/api/orders`, { ...auth(memberToken), data: { couponCode, ...RECEIVER } })
    expect(created.ok(), `建單失敗：${await created.text()}`).toBeTruthy()
    const order = await created.json()
    expect(order.status).toBe('UNPAID')

    // 建單當下：庫存已扣、券已鎖
    expect((await findProduct(request, PRODUCT)).stock).toBe(stockBefore - 1)
    expect((await myUnusedCoupons(request, memberToken)).some((c) => c.couponId === coupon.couponId)).toBeFalsy()

    // 頁面取消
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/orders')
    const card = page.locator(`[data-order-id="${order.orderId}"]`)
    if (!(await card.isVisible().catch(() => false))) {
      await page.locator('button, [role="tab"]').filter({ hasText: '待付款' }).first().click()
    }
    await expect(card).toBeVisible()
    // 操作按鈕在展開後的明細區塊裡
    await card.locator('.order-header').click()
    await expect(card.locator('.order-detail')).toBeVisible()

    page.on('dialog', (d) => d.accept())
    const cancelled = page.waitForResponse(
      (r) => r.url().endsWith(`/api/orders/${order.orderId}/cancel`) && r.request().method() === 'POST',
    )
    await card.getByRole('button', { name: '取消訂單' }).click()
    expect((await cancelled).ok()).toBeTruthy()

    // 後果：狀態、庫存、券
    const detail = await (await request.get(`${API}/api/orders/${order.orderId}`, auth(memberToken))).json()
    expect(detail.status).toBe('CANCELLED')
    expect((await findProduct(request, PRODUCT)).stock).toBe(stockBefore)
    expect((await myUnusedCoupons(request, memberToken)).some((c) => c.couponId === coupon.couponId)).toBeTruthy()

    // 頁面：卡片移到「取消/退款」分類並標成已取消
    await page.locator('button, [role="tab"]').filter({ hasText: '取消/退款' }).first().click()
    await expect(card).toBeVisible()
    await expect(card).toContainText('已取消')
  })

  /**
   * 同一支 cancel 端點對已付款訂單走的是另一條路：不取消、不動庫存，改建一筆待處理退款，
   * 訂單維持 PAID，由後台完成或駁回（OrderService.cancelOrder）。
   */
  test('已付款訂單按取消 → 變成退款申請，訂單維持 PAID、庫存不回補', async ({ request }) => {
    const memberToken = await apiToken(ACCOUNTS.member)
    const adminToken = await apiToken(ACCOUNTS.admin)
    await request.delete(`${API}/api/cart`, auth(memberToken))
    const product = await pickInStockProduct(request)
    const PRODUCT = product.name
    const stockBefore = product.stock
    await request.post(`${API}/api/cart/items`, { ...auth(memberToken), data: { productId: product.productId, quantity: 1 } })
    const order = await (await request.post(`${API}/api/orders`, { ...auth(memberToken), data: RECEIVER })).json()
    const paid = await request.post(`${API}/api/admin/orders/${order.orderId}/simulate-payment`, auth(adminToken))
    expect(paid.ok()).toBeTruthy()

    const res = await request.post(`${API}/api/orders/${order.orderId}/cancel`, { ...auth(memberToken), data: { reason: 'e2e 退款' } })
    expect(res.ok(), `退款申請失敗：${await res.text()}`).toBeTruthy()
    expect((await res.json()).status).toBe('PAID')
    expect((await findProduct(request, PRODUCT)).stock).toBe(stockBefore - 1)

    // 後台待處理退款清單裡看得到這筆
    const pendingBody = await (await request.get(`${API}/api/admin/orders/refunds/pending`, auth(adminToken))).json()
    const pending = (Array.isArray(pendingBody) ? pendingBody : pendingBody.content ?? pendingBody.items ?? [])
      .find((r) => r.orderId === order.orderId)
    expect(pending, '應有一筆對應這張訂單的待處理退款').toBeTruthy()
    expect(Number(pending.amount)).toBe(Number(order.totalAmount))

    // 收尾：駁回這筆退款，別讓它掛著擋到其他測試的出貨／完成按鈕
    const rejected = await request.post(`${API}/api/admin/orders/refunds/${pending.refundId}/reject`, {
      ...auth(adminToken), data: { reason: 'e2e 收尾' },
    })
    expect(rejected.ok(), `駁回退款失敗：${await rejected.text()}`).toBeTruthy()
  })
})
