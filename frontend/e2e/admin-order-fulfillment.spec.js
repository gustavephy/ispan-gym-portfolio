import { test, expect, ACCOUNTS, loginAs, apiToken, pickInStockProduct } from './fixtures.js'

/**
 * A6 管理端履約：付款 → 出貨 → 完成。
 *
 * <p>訂單狀態機的規則在 Order 實體裡：含實體商品的訂單必須先「已出貨」才能「已完成」，
 * 純課程訂單付款後可直接完成。這裡用 API 快速建單（購物車／直購 → 建單 → 模擬付款），
 * 實體商品那條用後台頁面按鈕走出貨與完成，確認頁面接的是同一套規則。
 *
 * <p>後台訂單頁支援 ?keyword=merchantTradeNo 進頁即查詢，且查到唯一一筆會自動展開——
 * 這是給退款頁「查看訂單」用的捷徑，測試直接沿用，省掉在列表裡找那一張卡。
 */

const API = 'http://localhost:8080'
const RECEIVER = { receiverName: '測試收件人', receiverPhone: '0912345678', receiverAddress: '台北市測試路 1 號' }
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

/** 購物車只放一件實體商品（挑目前庫存最多的），建一張未付款訂單 */
async function createProductOrder(request, memberToken) {
  await request.delete(`${API}/api/cart`, auth(memberToken))
  const { productId } = await pickInStockProduct(request)
  const add = await request.post(`${API}/api/cart/items`, { ...auth(memberToken), data: { productId, quantity: 1 } })
  expect(add.ok(), `加入購物車失敗：${await add.text()}`).toBeTruthy()
  const res = await request.post(`${API}/api/orders`, { ...auth(memberToken), data: RECEIVER })
  expect(res.ok(), `建單失敗：${await res.text()}`).toBeTruthy()
  return res.json()
}

/** 直購一份私教課方案（不經購物車） */
async function createCourseOrder(request, memberToken) {
  const body = await (await request.get(`${API}/api/private-courses`)).json()
  const list = Array.isArray(body) ? body : body.items ?? body.content ?? []
  const course = list.find((c) => Number(c.price) > 0)
  expect(course, '種子裡應有可購買的私教課方案').toBeTruthy()
  const res = await request.post(`${API}/api/orders/direct`, {
    ...auth(memberToken),
    data: { privateCourseId: course.privateCourseId, quantity: 1 },
  })
  expect(res.ok(), `直購建單失敗：${await res.text()}`).toBeTruthy()
  return res.json()
}

async function adminStatus(request, adminToken, orderId) {
  const res = await request.get(`${API}/api/admin/orders/${orderId}`, auth(adminToken))
  expect(res.ok()).toBeTruthy()
  return (await res.json()).status
}

async function simulatePayment(request, adminToken, orderId) {
  const res = await request.post(`${API}/api/admin/orders/${orderId}/simulate-payment`, auth(adminToken))
  expect(res.ok(), `模擬付款失敗：${await res.text()}`).toBeTruthy()
  expect(await adminStatus(request, adminToken, orderId)).toBe('PAID')
}

test.describe('A6 管理端履約', () => {
  test('實體商品訂單：未付款不能出貨、未出貨不能完成；後台頁面出貨→完成', async ({ page, request }) => {
    const memberToken = await apiToken(ACCOUNTS.member)
    const adminToken = await apiToken(ACCOUNTS.admin)
    const order = await createProductOrder(request, memberToken)
    expect(order.status).toBe('UNPAID')

    // 未付款就出貨：擋下
    const shipEarly = await request.post(`${API}/api/admin/orders/${order.orderId}/ship`, auth(adminToken))
    expect(shipEarly.ok()).toBeFalsy()

    await simulatePayment(request, adminToken, order.orderId)

    // 付款後直接完成：含實體商品，必須先出貨
    const completeEarly = await request.post(`${API}/api/admin/orders/${order.orderId}/complete`, auth(adminToken))
    expect(completeEarly.ok()).toBeFalsy()
    expect(await completeEarly.text()).toContain('先標記為已出貨')
    expect(await adminStatus(request, adminToken, order.orderId)).toBe('PAID')

    // 後台頁面：帶編號進頁，唯一一筆會自動展開
    await loginAs(page, ACCOUNTS.admin)
    await page.goto(`/admin/orders?keyword=${order.merchantTradeNo}`)
    const card = page.locator('.order-card').filter({ hasText: order.merchantTradeNo })
    await expect(card).toHaveCount(1)
    // 自動展開發生在列表載入之後，明細再多打一次 API；等明細區塊出現，等不到才自己點標題
    //（不能一進頁就判斷「還沒展開」去點——會把正在展開的卡片收起來）
    const detailPanel = card.locator('.order-detail')
    await expect(detailPanel).toBeVisible({ timeout: 5000 }).catch(() => card.locator('.order-header').click())
    await expect(detailPanel).toBeVisible()
    const shipBtn = card.getByRole('button', { name: '標記已出貨' })
    await expect(shipBtn).toBeVisible()

    const shipped = page.waitForResponse(
      (r) => r.url().endsWith(`/api/admin/orders/${order.orderId}/ship`) && r.request().method() === 'POST',
    )
    await shipBtn.click()
    expect((await shipped).ok()).toBeTruthy()
    await expect(card.locator('.status-badge').first()).toHaveText('已出貨')
    await expect(shipBtn).toBeHidden()

    const completed = page.waitForResponse(
      (r) => r.url().endsWith(`/api/admin/orders/${order.orderId}/complete`) && r.request().method() === 'POST',
    )
    await card.getByRole('button', { name: '標記完成' }).click()
    expect((await completed).ok()).toBeTruthy()
    await expect(card.locator('.status-badge').first()).toHaveText('已完成')
    expect(await adminStatus(request, adminToken, order.orderId)).toBe('COMPLETED')
  })

  test('課程訂單：付款後可直接完成，不需出貨', async ({ request }) => {
    const memberToken = await apiToken(ACCOUNTS.member)
    const adminToken = await apiToken(ACCOUNTS.admin)
    const order = await createCourseOrder(request, memberToken)
    await simulatePayment(request, adminToken, order.orderId)

    const done = await request.post(`${API}/api/admin/orders/${order.orderId}/complete`, auth(adminToken))
    expect(done.ok(), `完成失敗：${await done.text()}`).toBeTruthy()
    expect(await adminStatus(request, adminToken, order.orderId)).toBe('COMPLETED')

    // 已完成的訂單不能再改狀態
    const again = await request.post(`${API}/api/admin/orders/${order.orderId}/complete`, auth(adminToken))
    expect(again.ok()).toBeFalsy()
  })
})
