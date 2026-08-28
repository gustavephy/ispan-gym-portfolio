import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * B5 團課：行事曆瀏覽、報名、我的預約、取消。
 *
 * <p>資格是「有效訂閱」而不是額度（見 GroupClassService.bookSession）——播種的 wendy
 * 有 ACTIVE 訂閱。種子有 16 筆未來場次，但落在哪一週不固定，所以用行事曆的「下一週」
 * 往後翻，找到第一個可報名的場次為止，而不是假設本週就有。
 *
 * <p>報名紀錄由測試自己取消（額度／名額還原）；beforeEach 另外把 wendy 名下殘留的
 * BOOKED 紀錄清掉，避免上一輪中途失敗留下的報名讓「已報名此場次」擋住這一輪。
 */

const API = 'http://localhost:8080'

async function cancelAllBookings(request) {
  const token = await apiToken(ACCOUNTS.member)
  const res = await request.get(`${API}/api/member/group-classes/bookings?status=BOOKED&size=50`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const body = await res.json()
  const items = body.items ?? body.content ?? []
  for (const b of items) {
    await request.delete(`${API}/api/member/group-classes/bookings/${b.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  }
}

/** 切到清單視圖，往後翻週直到出現可報名的場次，點它打開詳情卡 */
async function openFirstBookableSession(page) {
  await page.locator('.fc-listWeek-button').click()
  for (let week = 0; week < 8; week++) {
    const bookBtn = page.locator('.gcs-list-book-btn:not([disabled])').first()
    if ((await bookBtn.count()) > 0) {
      await bookBtn.click()
      return
    }
    await page.locator('.fc-next-button').click()
    // FullCalendar 換週是同步重繪，但事件內容由 eventContent 重新建立，稍等一拍再查
    await page.waitForTimeout(300)
  }
  throw new Error('往後八週都找不到可報名的團課場次——請確認種子有未來場次')
}

test.describe('B5 團課報名', () => {
  test('未登入者看得到行事曆但沒有「我的預約」切換', async ({ page }) => {
    await page.goto('/group-classes')
    await expect(page.locator('.fc')).toBeVisible()
    await expect(page.getByRole('tablist', { name: '檢視切換' })).toHaveCount(0)
  })

  test.describe('已訂閱會員', () => {
    test.beforeEach(async ({ page, request }) => {
      await cancelAllBookings(request)
      await loginAs(page, ACCOUNTS.member)
      await page.goto('/group-classes')
      await expect(page.locator('.fc')).toBeVisible()
    })

    test('報名後出現在我的預約，取消後狀態轉為已取消', async ({ page, request }) => {
      await openFirstBookableSession(page)

      // 詳情卡：記下課程名稱，再按「報名」
      const modal = page.locator('.modal-overlay')
      await expect(modal).toBeVisible()
      const courseName = (await modal.locator('h3, h2').first().innerText()).trim()
      expect(courseName).not.toBe('')

      const booked = page.waitForResponse(
        (r) => /\/api\/member\/group-classes\/\d+\/booking$/.test(r.url()) && r.request().method() === 'POST',
      )
      await modal.getByRole('button', { name: '報名', exact: true }).click()
      const res = await booked
      expect(res.ok(), `報名失敗：${await res.text()}`).toBeTruthy()
      await expect(page.getByText('報名成功')).toBeVisible()

      // 後端也要真的有這筆 BOOKED
      const token = await apiToken(ACCOUNTS.member)
      const listRes = await request.get(`${API}/api/member/group-classes/bookings?status=BOOKED&size=50`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const listBody = await listRes.json()
      const mine = (listBody.items ?? listBody.content ?? []).find((b) => b.courseName === courseName)
      expect(mine, '我的預約 API 應該查得到剛報名的場次').toBeTruthy()

      // 切到「我的預約」頁籤看得到，狀態為已報名
      await page.getByRole('tablist', { name: '檢視切換' }).getByRole('tab', { name: '我的預約' }).click()
      // 限定在「我的預約」表格：行事曆的清單視圖也是 <table>，只是被 v-show 藏起來，
      // 不縮範圍會先命中那邊隱藏的列而永遠 toBeVisible 失敗
      const row = page.locator('.slot-table tbody tr').filter({ hasText: courseName }).first()
      await expect(row).toBeVisible()
      await expect(row).toContainText('已報名')

      // 取消：等 DELETE 回應，列的狀態轉為已取消（或該列消失，依篩選而定）
      page.on('dialog', (d) => d.accept())
      const cancelled = page.waitForResponse(
        (r) => /\/api\/member\/group-classes\/bookings\/\d+$/.test(r.url()) && r.request().method() === 'DELETE',
      )
      await row.getByRole('button', { name: '取消', exact: true }).click()
      expect((await cancelled).ok()).toBeTruthy()

      const listAfter = await request.get(`${API}/api/member/group-classes/bookings?status=BOOKED&size=50`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const afterBody = await listAfter.json()
      const stillBooked = (afterBody.items ?? afterBody.content ?? []).some((b) => b.id === mine.id)
      expect(stillBooked, '取消後不該還在 BOOKED 清單裡').toBeFalsy()
    })

    test('同一場次重複報名被後端擋下（409）', async ({ page, request }) => {
      await openFirstBookableSession(page)
      const modal = page.locator('.modal-overlay')
      const booked = page.waitForResponse(
        (r) => /\/api\/member\/group-classes\/\d+\/booking$/.test(r.url()) && r.request().method() === 'POST',
      )
      await modal.getByRole('button', { name: '報名', exact: true }).click()
      const first = await booked
      expect(first.ok()).toBeTruthy()
      const sessionId = Number(first.url().match(/group-classes\/(\d+)\/booking/)[1])

      const token = await apiToken(ACCOUNTS.member)
      const again = await request.post(`${API}/api/member/group-classes/${sessionId}/booking`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      expect(again.status()).toBe(409)
      expect((await again.json()).message).toContain('已報名')

      // 收尾：把這筆取消掉
      await cancelAllBookings(request)
    })
  })
})
