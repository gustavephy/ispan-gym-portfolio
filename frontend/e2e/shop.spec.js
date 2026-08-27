import { test, expect, ACCOUNTS, loginAs } from './fixtures.js'

/**
 * A3 商品瀏覽與購物車。
 *
 * <p>/products 的預設畫面是「分類區塊」，每個分類只列前 4 項，不是完整清單——
 * 想找特定商品要先搜尋。關鍵字是即時篩選（不必按搜尋鈕）。
 *
 * <p>選擇器：商品卡片沒有 data-testid，用 .item-card + 名稱過濾；
 * 購物車的按鈕都有帶商品名的 aria-label，那是最穩的定位點。
 */

/** 播種商品，名稱由 TestDataSeedRunner 寫死；不在預設畫面上，需搜尋才看得到 */
const PRODUCT = '乳清蛋白 1kg'

/** 預設畫面上就看得到的商品（運動補給分類的前幾項） */
const VISIBLE_PRODUCT = '運動毛巾'

function productCard(page, name) {
  return page.locator('.item-card').filter({ hasText: name }).first()
}

/** 搜尋並等結果穩定；關鍵字是即時篩選，不需要按鈕 */
async function searchProduct(page, keyword) {
  await page.getByPlaceholder('搜尋商品名稱...').fill(keyword)
  await expect(page.locator('.item-card').first()).toBeVisible()
}

test.describe('A3 商品瀏覽', () => {
  test('未登入也能瀏覽商品列表', async ({ page }) => {
    await page.goto('/products')

    await expect(productCard(page, VISIBLE_PRODUCT)).toBeVisible()
  })

  test('關鍵字搜尋會縮小結果', async ({ page }) => {
    await page.goto('/products')
    await expect(productCard(page, VISIBLE_PRODUCT)).toBeVisible()

    await searchProduct(page, '乳清')

    await expect(productCard(page, PRODUCT)).toBeVisible()
    // 搜尋「乳清」後不該還留著毛巾
    await expect(page.locator('.item-card').filter({ hasText: VISIBLE_PRODUCT })).toHaveCount(0)
  })

  test('點卡片進到商品詳情頁', async ({ page }) => {
    await page.goto('/products')
    await productCard(page, VISIBLE_PRODUCT).click()

    await expect(page).toHaveURL(/\/products\/\d+/)
    await expect(page.getByRole('heading', { name: VISIBLE_PRODUCT })).toBeVisible()
  })
})

test.describe('A3 購物車', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)
  })

  test('加入購物車後可調整數量並移除', async ({ page }) => {
    await page.goto('/products')
    await searchProduct(page, '乳清蛋白')

    const added = page.waitForResponse(
      (r) => r.url().includes('/api/cart') && r.request().method() === 'POST',
    )
    await productCard(page, PRODUCT).getByRole('button', { name: /加入購物車/ }).click()
    await added

    await page.goto('/cart')
    const qty = page.getByLabel(`${PRODUCT} 數量`, { exact: true })
    await expect(qty).toBeVisible()

    // aria-label 帶商品名，購物車有多樣商品時也不會點錯
    const before = Number(await qty.inputValue())
    await page.getByLabel(`增加 ${PRODUCT} 數量`).click()
    await expect(qty).toHaveValue(String(before + 1))

    await page.getByLabel(`移除 ${PRODUCT}`).click()
    await expect(page.getByLabel(`${PRODUCT} 數量`, { exact: true })).toHaveCount(0)
  })

  /**
   * /cart 掛了 requiresAuth，路由守衛會在頁面渲染前就攔下，
   * 所以未登入者看到的是登入頁而不是空購物車；redirect query 記住原本要去的地方。
   */
  test('未登入開購物車會被導向登入頁並記住來源', async ({ page, context }) => {
    await context.clearCookies()
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())

    await page.goto('/cart')

    await expect(page).toHaveURL('/login?redirect=/cart')
  })
})
