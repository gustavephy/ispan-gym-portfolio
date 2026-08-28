import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B4 從「我的課程」預約：額度卡的「立即預約」→ 該教練的獨立時段頁 → 預約成功。
 *
 * <p>coach-booking.spec 走的是「預約課程 → 點教練卡」那條路；這條是會員買了課程之後
 * 最自然的入口：我的課程 → 這份額度 → 直接到綁定教練的時段頁。之前這個按鈕曾經
 * 只導到 /reservation 主頁（靠教練名字比對，改成姓+名後就失效），所以要釘住
 * 「導向的是額度所綁教練的頁面」，不只是「有跳頁」。
 *
 * <p>時段由教練 API 先開好；預約後在收尾取消，額度退回（時段開在 30 天後，走退還分支）。
 */

const API = 'http://localhost:8080'
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

function futureSlot() {
  const d = new Date()
  d.setDate(d.getDate() + 30 + Math.floor(Math.random() * 60))
  const hour = 10 + Math.floor(Math.random() * 11)
  const date = d.toISOString().slice(0, 10)
  return {
    startAt: `${date}T${String(hour).padStart(2, '0')}:00:00`,
    endAt: `${date}T${String(hour + 1).padStart(2, '0')}:00:00`,
  }
}

async function createSlot(request) {
  const token = await apiToken(ACCOUNTS.trainer)
  const res = await request.post(`${API}/api/trainer/schedules`, { ...auth(token), data: futureSlot() })
  expect(res.ok(), `建立時段失敗：${await res.text()}`).toBeTruthy()
  return (await res.json()).id
}

/** 會員名下所有額度（含綁定教練） */
async function myQuotas(request) {
  const token = await apiToken(ACCOUNTS.member)
  const res = await request.get(`${API}/api/member/quotas`, auth(token))
  expect(res.ok()).toBeTruthy()
  return res.json()
}

/**
 * 對某位教練的剩餘堂數總和。會員對同一位教練可能有好幾列額度（播種的一份加上
 * course-catalog spec 買的方案），後端扣的不一定是畫面上按的那一列，所以比總和。
 */
async function remainingFor(request, trainerId) {
  return (await myQuotas(request))
    .filter((q) => q.trainer?.id === trainerId)
    .reduce((sum, q) => sum + (q.remainingCourse ?? 0), 0)
}

test.describe('B4 我的課程 → 立即預約', () => {
  test('立即預約導到額度綁定教練的時段頁，預約後該額度少一堂', async ({ page, request }) => {
    await createSlot(request)

    // 找一份有剩餘堂數且綁定教練的額度（播種的 wendy 對教練 id=1 有 10 堂）
    const target = (await myQuotas(request)).find((q) => q.trainer?.id && q.remainingCourse > 0)
    expect(target, 'wendy 應有綁定教練且仍有剩餘堂數的額度').toBeTruthy()
    const trainerId = target.trainer.id
    const before = await remainingFor(request, trainerId)

    await loginAs(page, ACCOUNTS.member)
    await page.goto('/member/courses')

    // 額度卡上有教練名字；用它鎖定那一張，避免點到別份額度
    const trainerName = target.trainer.displayName ?? target.trainer.name ?? ''
    const card = page.locator('.quota-card').filter({ hasText: trainerName }).first()
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '立即預約' }).click()

    // 導向的必須是這位教練的獨立時段頁
    await page.waitForURL(new RegExp(`/reservation/trainer/${trainerId}$`))
    await expect(page.getByRole('heading', { name: /的可預約時段/ })).toBeVisible()

    // 時段列表有分頁，剛開的那筆不一定在第一頁：預約畫面上第一個可預約時段即可
    const row = page.locator('.slot-table tbody tr').filter({ hasText: '預約' }).first()
    await expect(row).toBeVisible()
    page.on('dialog', (d) => d.accept()) // 預約前 confirm、成功後 alert 核銷碼
    const booked = page.waitForResponse((r) => r.url().includes('/booking') && r.request().method() === 'POST')
    await row.getByRole('button', { name: '預約' }).click()
    const res = await booked
    expect(res.ok(), `預約失敗：${await res.text()}`).toBeTruthy()
    const bookingId = (await res.json()).id

    try {
      expect(await remainingFor(request, trainerId)).toBe(before - 1)

      // 回到我的課程仍正常渲染（keep-alive 頁面 onActivated 會重抓）
      await page.goto('/member/courses')
      await expect(page.locator('.quota-card').filter({ hasText: trainerName }).first()).toBeVisible()
    } finally {
      const memberToken = await apiToken(ACCOUNTS.member)
      await request.delete(`${API}/api/member/schedules/${bookingId}/cancel`, auth(memberToken))
      expect(await remainingFor(request, trainerId), '收尾取消後額度應退回').toBe(before)
    }
  })

  test('額度用完的卡片不能預約', async ({ page, request }) => {
    const exhausted = (await myQuotas(request)).find((q) => q.remainingCourse === 0)
    test.skip(!exhausted, '目前沒有額度為 0 的方案，略過')
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/member/courses')
    const card = page.locator('.quota-card').filter({ hasText: '額度已用完' }).first()
    await expect(card).toBeVisible()
    await expect(card.getByRole('button', { name: '額度已用完' })).toBeDisabled()
  })
})
