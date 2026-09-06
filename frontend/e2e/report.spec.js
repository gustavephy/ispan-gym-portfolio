import { statSync } from 'node:fs'
import { test, expect, ACCOUNTS, loginAs } from './fixtures.js'

/**
 * A7 營運報表：生成快照、檢視、匯出、作廢（快照不提供刪除，誤生成只能作廢）。
 *
 * <p>報表是快照式的——生成當下把統計算完寫進 report_snapshot 與三張明細表，
 * 之後讀取不重算。同型別同區間重新生成是 append-only 版本化：新的成為現行版、
 * 舊的保留為歷史版本（不刪），下拉選單只列現行版——所以測試可以用固定日期
 * 重複跑而不會讓下拉累積垃圾（歷史版本累積在版本選單，不影響主清單）。
 *
 * <p>頁面的輸入框沒有 id，但都包在 <label> 裡且有文字 span，getByLabel 定位得到。
 */

/** 用固定的過去日期，重複跑會覆蓋同一份快照而不是一直新增 */
const REPORT_DATE = '2026-08-20'

/**
 * 生成一份日報快照。
 *
 * waitForResponse 必須在 click 之前註冊——後端回得很快，先點再等會錯過那個回應
 *（本機實測 POST 幾乎與 click 同時完成）。
 */
async function generateDailyReport(page, date = REPORT_DATE) {
  await page.getByRole('button', { name: /產生報表/ }).click()
  await page.getByLabel('報表類型').selectOption('DAILY')
  await page.getByLabel('日期').fill(date)

  const generated = page.waitForResponse(
    (r) => r.url().includes('/api/admin/reports') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '確定生成' }).click()
  const res = await generated
  expect(res.ok(), `生成失敗：${await res.text()}`).toBeTruthy()

  // 同區間重複生成是「舊版標記 superseded、新版為現行」：POST 回來的瞬間頁面可能
  // 還選著已被取代的舊版。等下拉真的切到新 id 再回傳，後續操作才可靠。
  const created = await res.json()
  // 頁面上的 select 順序：0 期間快選、1 品項型別、2 報表快照、3 版本（快選是 2026-09-07 加的，索引因此各 +1）
  await expect(page.locator('select').nth(2)).toHaveValue(String(created.id))
  return created
}

test.describe('A7 營運報表', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/reports')
  })

  test('即時統計顯示六張 KPI', async ({ page }) => {
    const kpis = page.locator('.kpi')
    // 六張全是即時統計：營收／件數／品項來自 /stats，訂單數／退款金額／新會員
    // 來自 /orders/reports/overview——退款卡已不再依賴報表快照，一進頁就該是六張
    await expect(kpis).toHaveCount(6)

    // 每張都該有數字，不是空殼
    for (const text of await kpis.allInnerTexts()) {
      expect(text).toMatch(/\d/)
    }
  })

  test('生成日報快照後可在下拉選單選到', async ({ page }) => {
    await generateDailyReport(page)

    // 生成後會自動選中新快照，操作按鈕跟著可用
    await expect(page.getByRole('button', { name: '下載 PDF' })).toBeEnabled()
    await expect(page.getByRole('button', { name: '排行 CSV' })).toBeEnabled()
  })

  test('同區間重複生成成為現行版，舊版保留為可回看的歷史版本', async ({ page }) => {
    const snapshotCount = async () => {
      const options = page.locator('select').nth(2).locator('option')
      return options.count()
    }

    await generateDailyReport(page)
    const before = await snapshotCount()

    // 同型別同區間再生成一次：主下拉（只列現行版）不長新項目……
    await generateDailyReport(page)
    expect(await snapshotCount()).toBe(before)

    // ……但版本選單出現：現行版在前、被取代的歷史版本仍可回看（append-only）
    const versionSelect = page.locator('select').nth(3)
    await expect(versionSelect).toBeVisible()
    const versionOptions = versionSelect.locator('option')
    await expect(versionOptions.first()).toContainText('現行版')
    expect(await versionOptions.count()).toBeGreaterThanOrEqual(2)
    await expect(versionOptions.nth(1)).toContainText('歷史版')
  })

  test('可下載 PDF 與排行 CSV', async ({ page }) => {
    await generateDailyReport(page)

    // 驗證檔案真的產出且有內容，但不解析——PDF/CSV 的內容正確性由後端測試負責
    const pdf = page.waitForEvent('download')
    await page.getByRole('button', { name: '下載 PDF' }).click()
    const pdfFile = await pdf
    expect(pdfFile.suggestedFilename()).toMatch(/\.pdf$/)
    expect(statSync(await pdfFile.path()).size).toBeGreaterThan(1000)

    const csv = page.waitForEvent('download')
    await page.getByRole('button', { name: '排行 CSV' }).click()
    const csvFile = await csv
    expect(csvFile.suggestedFilename()).toMatch(/\.csv$/)
    expect(statSync(await csvFile.path()).size).toBeGreaterThan(0)
  })

  test('作廢快照後預設從下拉隱藏，勾「顯示已作廢」可回看', async ({ page }) => {
    await generateDailyReport(page)

    const selector = page.locator('select').nth(2)
    const before = await selector.locator('option').count()

    // 作廢原因是 window.prompt；accept() 不帶值＝空原因
    page.on('dialog', (d) => d.accept())
    const voided = page.waitForResponse(
      (r) => /\/api\/admin\/reports\/\d+\/void$/.test(r.url()) && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '作廢', exact: true }).click()
    await voided

    // 快照不刪：預設清單隱藏作廢的；勾選後回來並標示「已作廢」
    //（歷次殘留的其他作廢快照也會一起回來，所以只驗「至少回到原數量」）
    await expect(selector.locator('option')).toHaveCount(before - 1)
    await page.getByLabel('顯示已作廢').check()
    await expect(selector.locator('option', { hasText: '已作廢' })).not.toHaveCount(0)
    expect(await selector.locator('option').count()).toBeGreaterThanOrEqual(before)
  })
})
