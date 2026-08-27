import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B3 私教課時段與預約，跨教練與會員兩種身分。
 *
 * <p>播種的 wendy 名下有 8 堂 trainer 1（emp-trainer）的額度，可以直接預約，
 * 不必每輪重跑一次購課流程（那條路由 checkout.spec.js 的模擬付款繞道負責）。
 *
 * <p>時段一律開在未來且每輪用不同時間，避免與既有資料或前一輪的時段撞在一起——
 * 後端會擋下同教練的重疊時段。
 */

const API = 'http://localhost:8080'

/**
 * 未來的可預約時段。日期與時間都帶隨機成分：後端會擋下同教練的重疊時段，
 * 固定時間的話重複跑第二次就會撞到自己前一輪建立的那個。
 */
function futureSlot() {
  const d = new Date()
  d.setDate(d.getDate() + 30 + Math.floor(Math.random() * 60))
  // 時間必須落在營業時間內（10:00–22:00，見 utils/businessHours.js），
  // 否則下拉選單根本沒有那個選項；取 10–20 點，留一小時給結束時間
  const hour = 10 + Math.floor(Math.random() * 11)
  const date = d.toISOString().slice(0, 10)
  return {
    date,
    // 畫面用 toLocaleString('zh-TW') 顯示成 2026/10/04，跟 input 用的 ISO 格式不同
    displayDate: date.replace(/-/g, '/'),
    startTime: `${String(hour).padStart(2, '0')}:00`,
    endTime: `${String(hour + 1).padStart(2, '0')}:00`,
  }
}

/** 以 API 建立一個可預約時段，回傳 slotId */
async function createSlot(request, slot) {
  const token = await apiToken(ACCOUNTS.trainer)
  const res = await request.post(`${API}/api/trainer/schedules`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      startAt: `${slot.date}T${slot.startTime}:00`,
      endAt: `${slot.date}T${slot.endTime}:00`,
    },
  })
  expect(res.ok(), `建立時段失敗：${await res.text()}`).toBeTruthy()
  return (await res.json()).id
}

/** 查詢會員名下對 trainer 1 的可用堂數 */
async function remainingQuota(request) {
  const token = await apiToken(ACCOUNTS.member)
  const res = await request.get(`${API}/api/member/quotas`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const list = await res.json()
  return list.reduce((sum, q) => sum + (q.remainingCourse ?? 0), 0)
}

test.describe('B3 教練排班', () => {
  test('教練建立時段後出現在自己的排班列表', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/trainer/schedules')

    const slot = futureSlot()
    await page.locator('#startDate').fill(slot.date)
    await page.locator('#startTime').selectOption(slot.startTime)
    await page.locator('#endDate').fill(slot.date)
    await page.locator('#endTime').selectOption(slot.endTime)

    const created = page.waitForResponse(
      (r) => r.url().includes('/api/trainer/schedules') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '建立時段' }).click()
    const res = await created
    expect(res.ok(), `建立失敗：${await res.text()}`).toBeTruthy()

    // 列表分頁且已有十幾筆時段，新建的落在哪一頁不一定——用日期篩選縮到只剩它
    await page.locator('#slotsDateFrom').fill(slot.date)
    await page.locator('#slotsDateTo').fill(slot.date)
    await expect(page.locator('tbody tr').filter({ hasText: slot.displayDate }).first()).toBeVisible()
  })

  test('重疊時段會被擋下', async ({ page, request }) => {
    const slot = futureSlot()
    await createSlot(request, slot)

    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/trainer/schedules')

    // 同一天同一時間再開一次
    await page.locator('#startDate').fill(slot.date)
    await page.locator('#startTime').selectOption(slot.startTime)
    await page.locator('#endDate').fill(slot.date)
    await page.locator('#endTime').selectOption(slot.endTime)
    await page.getByRole('button', { name: '建立時段' }).click()

    await expect(page.locator('.form-error')).toBeVisible()
  })
})

test.describe('B3 會員預約私教課', () => {
  test('預約後扣一堂額度，且出現在我的預約', async ({ page, request }) => {
    const slot = futureSlot()
    await createSlot(request, slot)

    const before = await remainingQuota(request)
    expect(before, '播種的 wendy 應該有可用堂數').toBeGreaterThan(0)

    await loginAs(page, ACCOUNTS.member)
    await page.goto('/reservation')

    // 點教練卡會「導向」該教練的獨立時段頁 /reservation/trainer/:id
    //（原本是同頁內嵌時段列表，重構後拆頁,見 TrainerSlotsView.vue）
    await page.locator('.trainer-card').first().click()
    await page.waitForURL(/\/reservation\/trainer\/\d+/)

    // 這個列表沒有日期篩選也有分頁，剛建立的時段不一定在第一頁；
    // 改成預約「畫面上第一個可預約時段」，重點在額度扣減與紀錄產生，不在特定那一筆
    const targetRow = page.locator('tbody tr').filter({ hasText: '預約' }).first()
    await expect(targetRow).toBeVisible()
    const rowText = (await targetRow.innerText()).split(/\s+/)[0]

    // 預約前有 window.confirm，成功後還會用 alert 顯示核銷碼——
    // 兩個都要接，不然 confirm 被自動取消，請求根本不會送出
    page.on('dialog', (d) => d.accept())

    const booked = page.waitForResponse(
      (r) => r.url().includes('/booking') && r.request().method() === 'POST',
    )
    await targetRow.getByRole('button', { name: '預約' }).click()
    const res = await booked
    expect(res.ok(), `預約失敗：${await res.text()}`).toBeTruthy()

    // 額度扣掉一堂
    expect(await remainingQuota(request)).toBe(before - 1)

    // 「我的預約」頁籤在 /reservation 主頁上,預約發生在獨立的時段頁,要先導回去
    await page.goto('/reservation')
    await page.getByRole('tab', { name: '我的預約' }).click()
    await expect(page.locator('tbody tr').filter({ hasText: rowText }).first()).toBeVisible()

    // 收尾：把這筆預約取消掉，額度退回。
    // 不清的話每跑一輪就少一堂，播種的 8 堂用完後整個 spec 就會開始失敗。
    const bookingId = (await res.json()).id
    const memberToken = await apiToken(ACCOUNTS.member)
    await request.delete(`${API}/api/member/schedules/${bookingId}/cancel`, {
      headers: { Authorization: `Bearer ${memberToken}` },
    })
    expect(await remainingQuota(request)).toBe(before)
  })

  /**
   * 距上課超過 72 小時取消 → 堂數退回。
   * 未滿 72 小時的取消會回 409 CONFIRMATION_REQUIRED 並視同消耗，
   * 這裡的時段都開在 30 天後，走的是退還那條分支。
   */
  test('取消預約後堂數退回', async ({ page, request }) => {
    const slot = futureSlot()
    const slotId = await createSlot(request, slot)

    const memberToken = await apiToken(ACCOUNTS.member)
    const before = await remainingQuota(request)

    const booked = await request.post(`${API}/api/member/schedules/${slotId}/booking`, {
      headers: { Authorization: `Bearer ${memberToken}` },
    })
    expect(booked.ok(), `預約失敗：${await booked.text()}`).toBeTruthy()
    expect(await remainingQuota(request)).toBe(before - 1)

    const bookingId = (await booked.json()).id

    await loginAs(page, ACCOUNTS.member)
    await page.goto('/reservation')
    await page.getByRole('tab', { name: '我的預約' }).click()

    // 這個列表也分頁，用日期篩選縮到只剩剛預約的那筆
    await page.locator('#myBookingsDateFrom').fill(slot.date)
    await page.locator('#myBookingsDateTo').fill(slot.date)

    const row = page.locator('tbody tr').filter({ hasText: slot.displayDate }).first()
    await expect(row).toBeVisible()

    const cancelled = page.waitForResponse(
      (r) => r.url().includes(`/${bookingId}/cancel`) && r.request().method() === 'DELETE',
    )
    await row.getByRole('button', { name: '取消' }).click()
    await cancelled

    // 距上課還很久，堂數應該退回
    expect(await remainingQuota(request)).toBe(before)
  })
})
