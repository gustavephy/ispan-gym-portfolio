import { statSync } from 'node:fs'
import { test, expect, ACCOUNTS, loginAs } from './fixtures.js'

/**
 * A7 營運報表：生成快照、檢視、匯出、刪除。
 *
 * <p>報表是快照式的——生成當下把統計算完寫進 report_snapshot 與三張明細表，
 * 之後讀取不重算。同型別同區間重新生成會覆蓋舊的那份（同一交易內刪舊插新），
 * 所以測試可以用固定日期重複跑而不會累積垃圾。
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
}

test.describe('A7 營運報表', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/reports')
  })

  test('即時統計顯示四張 KPI', async ({ page }) => {
    const kpis = page.locator('.kpi')
    await expect(kpis).toHaveCount(4)

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

  test('同區間重複生成會覆蓋而不是新增一份', async ({ page }) => {
    const snapshotCount = async () => {
      const options = page.locator('select').nth(1).locator('option')
      return options.count()
    }

    await generateDailyReport(page)
    const before = await snapshotCount()

    // 同型別同區間再生成一次
    await generateDailyReport(page)

    expect(await snapshotCount()).toBe(before)
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

  test('刪除快照後從下拉選單消失', async ({ page }) => {
    await generateDailyReport(page)

    const selector = page.locator('select').nth(1)
    const before = await selector.locator('option').count()

    // 刪除是 window.confirm
    page.on('dialog', (d) => d.accept())
    const deleted = page.waitForResponse(
      (r) => r.url().includes('/api/admin/reports/') && r.request().method() === 'DELETE',
    )
    await page.getByRole('button', { name: '刪除' }).click()
    await deleted

    await expect(selector.locator('option')).toHaveCount(before - 1)
  })
})
