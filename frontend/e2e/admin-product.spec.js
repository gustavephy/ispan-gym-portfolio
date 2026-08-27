import { execFileSync } from 'node:child_process'
import { test, expect, ACCOUNTS, loginAs, uniqueSuffix } from './fixtures.js'

/**
 * A5 後台商品與庫存管理。
 *
 * <p>每個案例都用帶時間戳的商品名，避免重複跑污染共用的開發資料庫，
 * 也避免與組員手動建立的資料撞名。
 *
 * <p>新增表單的 label 沒有用 for/id 綁 input，getByLabel 定位不到，
 * 只能靠 placeholder；下架走的是 window.confirm，需要掛 dialog handler。
 */

/**
 * 跑完清掉本檔建立的商品。
 *
 * <p>下架端點只是把 status 改成 INACTIVE，列不會消失，累積下來會把
 * /products 預設畫面的播種商品擠掉（各分類只列前 4 項，且新的排前面），
 * 連帶讓 shop.spec.js 找不到「運動毛巾」而失敗。
 *
 * <p>已被結帳測試買過的商品有 order_item 參照，刪不掉（FK_oi_product），
 * 只能改成 INACTIVE——前台端點固定只回 ACTIVE，一樣不會再擋到播種商品。
 */
test.afterAll(() => {
  const sql = `
    DELETE FROM product
    WHERE name LIKE 'E2E %'
      AND product_id NOT IN (SELECT product_id FROM order_item WHERE product_id IS NOT NULL);
    UPDATE product SET status = 'INACTIVE' WHERE name LIKE 'E2E %';`
  execFileSync(
    'sqlcmd',
    ['-S', 'localhost', '-d', 'ispan-proj-gymdb', '-U', 'sa', '-P', process.env.DB_PASSWORD,
      '-C', '-Q', sql],
    { encoding: 'utf8' },
  )
})

/** 商品列。用 tr 過濾名稱，比從 td 往上找穩定 */
function productRow(page, name) {
  return page.locator('tbody tr').filter({ hasText: name }).first()
}

async function createProduct(page, { name, price, stock }) {
  await page.goto('/admin/products')
  await page.getByRole('button', { name: /新增商品/ }).click()

  await page.getByPlaceholder('例如: 智慧降噪水壺').fill(name)
  await page.locator('select').first().selectOption({ index: 1 })
  await page.getByPlaceholder('請輸入金額').fill(String(price))
  await page.getByPlaceholder('庫存數量').fill(String(stock))
  await page.getByPlaceholder('請填寫此項目的特色、規格或內容介紹...').fill('E2E 測試建立，可安全刪除')

  const created = page.waitForResponse(
    (r) => r.url().includes('/products') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '確認發佈上架' }).click()
  await created
}

test.describe('A5 後台商品管理', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)
  })

  test('新增商品後出現在列表且狀態為上架中', async ({ page }) => {
    const name = `E2E 測試商品 ${uniqueSuffix()}`
    await createProduct(page, { name, price: 1234, stock: 50 })

    // 列表有分頁且商品有 60 幾筆，新建的不一定落在第一頁，先搜尋再斷言
    await page.getByPlaceholder('搜尋名稱、說明...').fill(name)

    const row = productRow(page, name)
    await expect(row).toBeVisible()
    await expect(row).toContainText('上架中')
    await expect(row).toContainText('1,234')
  })

  test('下架後狀態轉為已下架，且前台商城看不到', async ({ page }) => {
    const name = `E2E 下架測試 ${uniqueSuffix()}`
    await createProduct(page, { name, price: 999, stock: 10 })

    await page.getByPlaceholder('搜尋名稱、說明...').fill(name)

    // 下架是 window.confirm，不接受就不會送出
    page.on('dialog', (d) => d.accept())
    const removed = page.waitForResponse(
      (r) => r.url().includes('/products/') && r.request().method() === 'DELETE',
    )
    await productRow(page, name).getByRole('button', { name: '下架' }).click()
    await removed

    // 預設的「全部實體商品」只列 ACTIVE，下架後那一列會消失；
    // 切到「已下架商品」才看得到——這個篩選先前是壞的（後台誤用前台端點，
    // 而前台端點固定只回 ACTIVE），現在後台有自己的 GET /api/admin/products
    await expect(productRow(page, name)).toHaveCount(0)
    await page.getByRole('button', { name: '已下架商品' }).click()
    await expect(productRow(page, name)).toContainText('已下架')

    // 跨頁一致性：下架的商品不該還出現在前台商城
    await page.goto('/products')
    await page.getByPlaceholder('搜尋商品名稱...').fill(name)
    await expect(page.locator('.item-card').filter({ hasText: name })).toHaveCount(0)
  })

  /**
   * 庫存低於安全門檻的商品應該進到「低於安全庫存」分頁。
   *
   * 這裡刻意走那個分頁而不是「全部在架商品」——庫存頁沒有搜尋功能，
   * 全部商品有 60 幾筆且分頁，新建的商品不一定落在第一頁，找不到。
   */
  test('低庫存商品進到低於安全庫存分頁並顯示警示', async ({ page }) => {
    const name = `E2E 低庫存 ${uniqueSuffix()}`
    await createProduct(page, { name, price: 300, stock: 2 })

    await page.goto('/admin/inventory')
    await page.getByRole('button', { name: /低於安全庫存/ }).click()

    const row = productRow(page, name)
    await expect(row).toBeVisible()
    await expect(row).toContainText('庫存緊張')
    await expect(row).toContainText('前往補貨')
  })

  /** 庫存充足的商品不該出現在低庫存分頁 */
  test('庫存充足的商品不在低庫存分頁', async ({ page }) => {
    const name = `E2E 庫存充足 ${uniqueSuffix()}`
    await createProduct(page, { name, price: 500, stock: 88 })

    await page.goto('/admin/inventory')
    await page.getByRole('button', { name: /低於安全庫存/ }).click()

    await expect(productRow(page, name)).toHaveCount(0)
  })
})
