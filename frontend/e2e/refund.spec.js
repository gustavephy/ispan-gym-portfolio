import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B4 退款申請與審核。跨會員與管理者兩種身分，兩條分支（完成／駁回）都要走到。
 *
 * <p>每個案例都需要一筆全新的已付款訂單，用 API 直接建：走 UI 建單要填收件資訊、
 * 過確認彈窗、再繞過綠界，那條路 checkout.spec.js 已經驗證過了，這裡的重點是
 * 建單之後的退款流程，不必每次重跑一遍。
 */

const API = 'http://localhost:8080'

/**
 * 建立一筆已付款訂單，回傳 { orderId, merchantTradeNo }。
 *
 * 步驟：清空購物車 → 加一件商品 → 結帳建單 → 以 ADMIN 模擬付款。
 * 模擬付款走的是與綠界付款成功完全相同的 markPaid + fulfillPaidOrder。
 */
async function createPaidOrder(request) {
  const memberToken = await apiToken(ACCOUNTS.member)
  const adminToken = await apiToken(ACCOUNTS.admin)
  const auth = (t) => ({ Authorization: `Bearer ${t}` })

  // 清掉購物車殘留，金額才可預期
  const cart = await (await request.get(`${API}/api/cart`, { headers: auth(memberToken) })).json()
  for (const item of cart.items ?? []) {
    await request.delete(`${API}/api/cart/items/${item.cartItemId}`, { headers: auth(memberToken) })
  }

  // 挑一件有庫存的商品加入
  const products = await (await request.get(`${API}/api/products?size=20`)).json()
  const product = (products.content ?? []).find((p) => p.stock > 0)
  expect(product, '找不到有庫存的商品可供測試').toBeTruthy()
  await request.post(`${API}/api/cart/items`, {
    headers: auth(memberToken),
    data: { productId: product.productId, quantity: 1 },
  })

  const orderRes = await request.post(`${API}/api/orders`, {
    headers: auth(memberToken),
    data: {
      receiverName: 'E2E 退款測試',
      receiverPhone: '0912345678',
      receiverAddress: '台北市大安區信義路四段 1 號',
    },
  })
  expect(orderRes.ok(), `建單失敗：${await orderRes.text()}`).toBeTruthy()
  const order = await orderRes.json()

  const paid = await request.post(`${API}/api/admin/orders/${order.orderId}/simulate-payment`, {
    headers: auth(adminToken),
  })
  expect(paid.ok(), '模擬付款失敗').toBeTruthy()

  return { orderId: order.orderId, merchantTradeNo: order.merchantTradeNo }
}

/** 待處理退款清單裡對應這筆訂單的那一列 */
function refundRow(page, merchantTradeNo) {
  return page.locator('tbody tr').filter({ hasText: merchantTradeNo }).first()
}

test.describe('B4 退款申請與審核', () => {
  test('會員申請退款後出現在後台待處理清單', async ({ page, request }) => {
    const { orderId, merchantTradeNo } = await createPaidOrder(request)

    await loginAs(page, ACCOUNTS.member)
    await page.goto(`/orders/${orderId}`)

    await page.getByRole('button', { name: '申請退款/退貨' }).click()
    await page.getByLabel('詳細說明').fill('E2E 測試：商品與描述不符')

    const submitted = page.waitForResponse(
      (r) => r.url().includes('/cancel') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '送出申請' }).click()
    await submitted

    // 後台應該看得到這筆申請，且帶著會員填的理由
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/refunds')
    await expect(refundRow(page, merchantTradeNo)).toContainText('商品與描述不符')
  })

  test('完成退款後訂單轉為已退款', async ({ page, request }) => {
    const { orderId, merchantTradeNo } = await createPaidOrder(request)

    // 申請退款走 API，UI 路徑上一個案例已驗證過
    const memberToken = await apiToken(ACCOUNTS.member)
    await request.post(`${API}/api/orders/${orderId}/cancel`, {
      headers: { Authorization: `Bearer ${memberToken}` },
      data: { reason: 'E2E 測試：完成分支' },
    })

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/refunds')

    // 完成退款是 window.confirm
    page.on('dialog', (d) => d.accept())
    const completed = page.waitForResponse(
      (r) => r.url().includes('/refunds/') && r.request().method() === 'POST',
    )
    await refundRow(page, merchantTradeNo).getByRole('button', { name: '完成退款' }).click()
    await completed

    // 處理完就從待處理清單消失
    await expect(refundRow(page, merchantTradeNo)).toHaveCount(0)

    const adminToken = await apiToken(ACCOUNTS.admin)
    const order = await (
      await request.get(`${API}/api/admin/orders/${orderId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
    ).json()
    expect(order.status).toBe('REFUNDED')
  })

  test('駁回退款後訂單狀態不變', async ({ page, request }) => {
    const { orderId, merchantTradeNo } = await createPaidOrder(request)

    const memberToken = await apiToken(ACCOUNTS.member)
    await request.post(`${API}/api/orders/${orderId}/cancel`, {
      headers: { Authorization: `Bearer ${memberToken}` },
      data: { reason: 'E2E 測試：駁回分支' },
    })

    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/refunds')

    // 駁回是 window.prompt，要填駁回原因
    page.on('dialog', (d) => d.accept('E2E 測試：不符合退款條件'))
    const rejected = page.waitForResponse(
      (r) => r.url().includes('/reject') && r.request().method() === 'POST',
    )
    await refundRow(page, merchantTradeNo).getByRole('button', { name: '駁回' }).click()
    await rejected

    await expect(refundRow(page, merchantTradeNo)).toHaveCount(0)

    // 駁回不影響訂單狀態，應該維持已付款
    const adminToken = await apiToken(ACCOUNTS.admin)
    const order = await (
      await request.get(`${API}/api/admin/orders/${orderId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
    ).json()
    expect(order.status).toBe('PAID')
  })

  /** 有待處理退款時不該讓訂單繼續履行——出貨與退款邏輯上互斥 */
  test('有待處理退款時擋下出貨', async ({ request }) => {
    const { orderId } = await createPaidOrder(request)
    const memberToken = await apiToken(ACCOUNTS.member)
    const adminToken = await apiToken(ACCOUNTS.admin)

    await request.post(`${API}/api/orders/${orderId}/cancel`, {
      headers: { Authorization: `Bearer ${memberToken}` },
      data: { reason: 'E2E 測試：出貨互斥' },
    })

    const ship = await request.post(`${API}/api/admin/orders/${orderId}/ship`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(ship.status()).toBe(409)
    expect((await ship.json()).message).toContain('退款申請正在處理中')
  })
})
