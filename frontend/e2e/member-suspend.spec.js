import { test, expect, ACCOUNTS, loginAs, apiToken } from './fixtures.js'

/**
 * A8 會員停權即時生效。
 *
 * <p>角色是每個請求當下從資料庫展開的（AccountAuthorityResolver），不是簽在 token 裡——
 * 所以停權不用等 token 過期：純會員被停權後，手上的 token 下一個請求就變成匿名（401），
 * 前端攔截器隨即登出並導向登入頁；恢復後同一個 token 又能用（credentials_version 沒變）。
 *
 * <p>對象用播種的純會員 wendy，finally 一定恢復，否則後面的 spec 全部登不進。
 */

const API = 'http://localhost:8080'
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

async function findMemberId(request, adminToken, email) {
  const res = await request.get(`${API}/api/admin/members?keyword=${encodeURIComponent(email)}&size=20`, auth(adminToken))
  const body = await res.json()
  const hit = (body.content ?? body.items ?? []).find((m) => m.email === email)
  expect(hit, `後台會員列表找不到 ${email}`).toBeTruthy()
  return hit.memberId
}

test.describe('A8 會員停權', () => {
  test('停權後 token 立即失效並被導向登入頁；恢復後同一 token 又能用', async ({ page, request }) => {
    const adminToken = await apiToken(ACCOUNTS.admin)
    const memberToken = await apiToken(ACCOUNTS.member)
    const memberId = await findMemberId(request, adminToken, ACCOUNTS.member.email)

    await loginAs(page, ACCOUNTS.member)
    await page.goto('/orders')
    await expect(page).toHaveURL(/\/orders/)

    let suspended = false
    try {
      const sus = await request.post(`${API}/api/admin/members/${memberId}/suspend`, auth(adminToken))
      expect(sus.ok(), `停權失敗：${await sus.text()}`).toBeTruthy()
      suspended = true

      // API 層：同一個 token 下一個請求就是 401（不等過期）
      expect((await request.get(`${API}/api/account/me`, auth(memberToken))).status()).toBe(401)

      // 前端層：重新進需要登入的頁，401 觸發攔截器登出並導向登入頁
      await page.goto('/orders')
      await expect(page).toHaveURL(/\/login/)
      const stored = await page.evaluate(() => localStorage.getItem('auth'))
      expect(stored, '攔截器應已清掉登入狀態').toBeNull()

      // 重複停權被擋下
      const again = await request.post(`${API}/api/admin/members/${memberId}/suspend`, auth(adminToken))
      expect(again.status()).toBe(409)
    } finally {
      if (suspended) {
        const act = await request.post(`${API}/api/admin/members/${memberId}/activate`, auth(adminToken))
        expect(act.ok(), `恢復失敗：${await act.text()}`).toBeTruthy()
      }
    }

    // 恢復後：同一個 token（版本沒變）又能用
    expect((await request.get(`${API}/api/account/me`, auth(memberToken))).ok()).toBeTruthy()
  })
})
