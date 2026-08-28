import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B1 商品購買完整流程。
 *
 * <p>結帳送出後 submitEcpayForm() 會 POST 到綠界、瀏覽器離開站點，測試走不下去。
 * 這裡用兩個手段接回站內：
 * <ul>
 *   <li>攔截 POST /api/orders 的回應拿到 orderId</li>
 *   <li>用 page.route() 擋下往綠界的導向，讓瀏覽器留在原地</li>
 * </ul>
 * 接著以 ADMIN 身分呼叫 POST /api/admin/orders/{id}/simulate-payment
 *（走與真實付款成功完全相同的 markPaid + fulfillPaidOrder），再回前台驗證履約結果。
 */

const API = 'http://localhost:8080'
// 46 筆版種子的實際商品名（庫存 45），舊名「乳清蛋白 1kg」已不存在
const PRODUCT = '分離乳清蛋白-香草'

function productCard(page, name) {
  return page.locator('.item-card').filter({ hasText: name }).first()
}

/** 清空購物車，避免前一個測試或手動操作殘留的品項影響金額與斷言 */
async function clearCart(page, account) {
  const token = await apiToken(account)
  const res = await page.request.get(`${API}/api/cart`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const cart = await res.json()
  for (const item of cart.items ?? []) {
    await page.request.delete(`${API}/api/cart/items/${item.cartItemId}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  }
}

/** 攔下往綠界的表單送出，讓測試留在站內 */
async function blockEcpayRedirect(page) {
  await page.route(/ecpay\.com\.tw/, (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body>ECPAY_STUB</body></html>' }),
  )
}

async function addProductToCart(page, name) {
  await page.goto('/products')
  // 先等預設清單渲染完再輸入，避免初始載入期間搜尋框被重新渲染而吃掉輸入（見 shop.spec）
  await expect(page.locator('.item-card').first()).toBeVisible()
  await page.getByPlaceholder('搜尋商品名稱...').fill(name)
  await expect(productCard(page, name)).toBeVisible()

  const added = page.waitForResponse(
    (r) => r.url().includes('/api/cart') && r.request().method() === 'POST',
  )
  await productCard(page, name).getByRole('button', { name: /加入購物車/ }).click()
  await added
}

test.describe('B1 商品購買完整流程', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)
    await clearCart(page, ACCOUNTS.member)
  })

  test('結帳建立訂單後，模擬付款讓訂單轉為已付款', async ({ page }) => {
    await blockEcpayRedirect(page)
    await addProductToCart(page, PRODUCT)

    await page.goto('/checkout')
    await page.getByPlaceholder('選填').first().fill('E2E 測試收件人')
    await page.getByPlaceholder('選填').nth(1).fill('0912345678')
    await page.locator('select').first().selectOption('台北市')
    await page.locator('select').nth(1).selectOption('大安區')
    await page.getByPlaceholder('路名、巷弄、門牌號碼等詳細地址').fill('信義路四段 1 號')

    // 攔下建單回應取得 orderId——這是接回站內的關鍵
    const orderResponse = page.waitForResponse(
      (r) => r.url().endsWith('/api/orders') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '確認結帳並前往付款' }).click()
    await page.getByRole('button', { name: /確認送出/ }).click()
    const orderId = (await (await orderResponse).json()).orderId
    expect(orderId).toBeTruthy()

    // 訂單剛建立時應該是未付款
    const adminToken = await apiToken(ACCOUNTS.admin)
    const before = await page.request.get(`${API}/api/admin/orders/${orderId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect((await before.json()).status).toBe('UNPAID')

    // 繞過綠界：走與真實付款成功相同的履約路徑
    const paid = await page.request.post(
      `${API}/api/admin/orders/${orderId}/simulate-payment`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    )
    expect(paid.ok()).toBeTruthy()
    expect((await paid.json()).status).toBe('PAID')

    // 回到前台，會員的訂單列表應該看得到這筆已付款訂單
    await page.goto(`/orders/${orderId}`)
    await expect(page.getByText('已付款').first()).toBeVisible()
  })

  /**
   * 購物車空的時候整張結帳表單不會渲染，直接顯示空狀態——
   * openConfirm() 裡那道「購物車是空的」檢查其實從 UI 走不到，是防禦性的第二層。
   */
  test('購物車是空的時候不顯示結帳表單', async ({ page }) => {
    await page.goto('/checkout')

    await expect(page.getByText('購物車是空的，無法結帳')).toBeVisible()
    await expect(page.getByRole('button', { name: '確認結帳並前往付款' })).toHaveCount(0)
  })

  test('沒填收件地址時擋下結帳', async ({ page }) => {
    await addProductToCart(page, PRODUCT)

    await page.goto('/checkout')
    await page.getByRole('button', { name: '確認結帳並前往付款' }).click()

    await expect(page.getByText('請填寫收件地址')).toBeVisible()
  })
})
