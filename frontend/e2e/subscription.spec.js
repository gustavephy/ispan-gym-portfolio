import { execFileSync } from 'node:child_process'
import { test, expect, ACCOUNTS, PASSWORD, loginAs, apiToken, uniqueSuffix, registerActivatedMember } from './fixtures.js'

/**
 * 直接查資料庫拿 email 驗證 token。
 *
 * 沒有 API 可以查（刻意的——那等於繞過驗證機制），本機也收不到信
 *（未設 MAIL_USERNAME），所以測試只能從 DB 取。這是這個 spec 唯一
 * 直接碰資料庫的地方，其餘一律走 API 或 UI。
 */
function readVerificationToken(email) {
  const sql = `SET NOCOUNT ON; SELECT TOP 1 CAST(v.token AS VARCHAR(40))
    FROM email_verification v JOIN account a ON a.account_id = v.account_id
    WHERE a.email = '${email}' AND v.verified_at IS NULL
    ORDER BY v.id DESC;`
  return execFileSync(
    'sqlcmd',
    ['-S', 'localhost', '-d', 'ispan-proj-gymdb', '-U', 'sa', '-P', process.env.DB_PASSWORD,
      '-C', '-h', '-1', '-W', '-Q', sql],
    { encoding: 'utf8' },
  ).trim()
}

/** 以 API 登入取得 session（新註冊的帳號不在 fixtures 的快取名單裡） */
async function loginByApi(request, email) {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { email, password: PASSWORD },
  })
  expect(res.ok(), `新會員登入失敗：${await res.text()}`).toBeTruthy()
  return res.json()
}

/**
 * B2 會籍訂閱購買。
 *
 * <p>後端不允許提前續約（名下有未到期訂閱時 createSubscriptionOrder 直接擋下），
 * 而播種的 wendy 名下已有一筆效期到 11 月的 ACTIVE 訂閱，沒辦法用她測購買。
 * 所以每輪註冊一個全新會員：註冊 → 讀 DB 拿驗證 token → 走真實的 verify-email
 * 端點啟用帳號 → 才能登入與訂閱。
 *
 * <p>訂閱結帳同樣會導向綠界，繞法與 checkout.spec.js 一致：攔截建單回應拿 orderId，
 * 再以 ADMIN 呼叫 simulate-payment 接回站內。
 */

const API = 'http://localhost:8080'

/**
 * 註冊並啟用一個新會員，回傳其 email。
 *
 * 驗證 token 直接從註冊回應拿不到（只寄信），這裡走後端提供的查詢端點；
 * 本機沒設 MAIL_USERNAME 時信寄不出去，但 token 已經寫入資料庫，
 * verify-email 仍可正常完成——見 MemberRegisterService 的註解。
 */
async function registerActiveMember(request) {
  const email = `e2e-sub-${uniqueSuffix()}@local.test`

  const res = await request.post(`${API}/api/members/register`, {
    data: {
      email,
      password: PASSWORD,
      lastName: '訂閱',
      firstName: '測試',
      phone: '0955123456',
      dob: '1995-05-05',
    },
  })
  expect(res.ok(), `註冊失敗：${await res.text()}`).toBeTruthy()

  return email
}

test.describe('B2 會籍訂閱', () => {
  test('已有有效訂閱的會員看得到目前會籍且不能重複訂閱', async ({ page }) => {
    // wendy 名下有播種的 ACTIVE 訂閱
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/subscription')

    await expect(page.getByText('目前使用會籍')).toBeVisible()
    // 每張方案卡各有一顆「您已是訂閱會員」，用 first() 避開 strict mode；三張都該是 disabled
    const disabledButtons = page.getByRole('button', { name: '您已是訂閱會員' })
    await expect(disabledButtons.first()).toBeDisabled()
    await expect(disabledButtons).toHaveCount(await page.locator('.plan-card').count())
  })

  test('未登入者看得到方案，點訂閱被導向登入頁', async ({ page, context }) => {
    await context.clearCookies()
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())

    await page.goto('/subscription')

    // 方案本身是公開的，未登入也該看得到
    await expect(page.locator('.plan-card').first()).toBeVisible()

    await page.locator('.plan-card').first().getByRole('button').click()
    await expect(page).toHaveURL('/login?redirect=/subscription')
  })

  /**
   * 回歸測試：純管理員沒有 member 列，後端的訂閱端點會回 401，
   * 攔截器把 401 當成 session 過期就會把剛登入的人踢出去。
   * purchase() 必須在發請求前先擋下——這個守衛先前被後續合併移除過一次。
   */
  test('非會員（純管理員）點訂閱不會被登出', async ({ page }) => {
    await loginAs(page, ACCOUNTS.adminOnly)
    await page.goto('/subscription')

    await expect(page.locator('.plan-card').first()).toBeVisible()
    await page.locator('.plan-card').first().getByRole('button').click()

    await expect(page.getByText('此帳號沒有會員身分，無法訂閱會籍')).toBeVisible()
    // 關鍵斷言：人還在原地、還登著
    await expect(page).toHaveURL('/subscription')
    await expect(page.getByTitle(ACCOUNTS.adminOnly.email)).toBeVisible()
  })

  /**
   * 完整購買流程：新會員從沒有會籍 → 建立訂閱訂單 → 模擬付款 → 訂閱生效。
   * 履約結果（訂閱真的建立起來）要跨到別的頁面才看得到，這是這條流程的價值所在。
   */
  test('新會員訂閱後會籍生效', async ({ page, request }) => {
    const email = await registerActiveMember(request)

    // 新註冊會員是 PENDING，要完成 email 驗證才登得進去。
    // 驗證 token 只存在於信件與資料庫，沒有 API 可查（本機也沒設 MAIL_USERNAME 收不到信），
    // 只能直接讀 DB 拿 token，再走真實的 verify-email 端點完成啟用。
    const token = readVerificationToken(email)
    expect(token, '取不到驗證 token').toBeTruthy()
    const verified = await request.get(`${API}/api/accounts/verify-email?token=${token}`)
    expect(verified.ok(), `驗證失敗：${await verified.text()}`).toBeTruthy()

    // 以新會員登入，此時名下應該沒有任何會籍
    const session = await loginByApi(request, email)
    await page.goto('/')
    await page.evaluate((s) => {
      localStorage.setItem('auth', JSON.stringify({
        token: s.accessToken, accountId: s.accountId, email: s.email, roles: s.roles,
      }))
    }, session)
    await page.goto('/subscription')
    await expect(page.getByText('目前使用會籍')).toHaveCount(0)

    // 送出訂閱：擋下往綠界的導向，讓瀏覽器留在站內
    await page.route(/ecpay\.com\.tw/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html', body: 'STUB' }),
    )
    const orderResponse = page.waitForResponse(
      (r) => r.url().includes('/direct/subscription/checkout') && r.request().method() === 'POST',
    )
    await page.locator('.plan-card').first().getByRole('button').click()
    await orderResponse

    // 從會員自己的訂單列表拿 orderId——直接讀 checkout 回應的 body 不可靠，
    // 表單送出會讓瀏覽器開始導頁，回應內容可能已經被釋放
    const orders = await (
      await request.get(`${API}/api/orders`, {
        headers: { Authorization: `Bearer ${session.accessToken}` },
      })
    ).json()
    const orderId = (orders.items ?? orders.content ?? orders)[0]?.orderId
    expect(orderId, '找不到剛建立的訂閱訂單').toBeTruthy()

    // 繞過綠界完成付款
    const adminToken = await apiToken(ACCOUNTS.admin)
    const paid = await request.post(`${API}/api/admin/orders/${orderId}/simulate-payment`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })
    expect(paid.ok(), '模擬付款失敗').toBeTruthy()

    // 履約結果：回到會籍頁應該看得到生效中的會籍
    await page.goto('/subscription')
    await expect(page.getByText('目前使用會籍')).toBeVisible()
    // 每張方案卡各有一顆「您已是訂閱會員」，用 first() 避開 strict mode；三張都該是 disabled
    const disabledButtons = page.getByRole('button', { name: '您已是訂閱會員' })
    await expect(disabledButtons.first()).toBeDisabled()
    await expect(disabledButtons).toHaveCount(await page.locator('.plan-card').count())
  })

  /**
   * 防重複建單：按了訂閱 → 到綠界按上一頁 → 回來再按一次，曾經 10 秒建出兩張未付款訂單。
   * 現在第二次按會找到既有的未付款訂單直接續付（重新拿綠界表單），不再建新單。
   */
  test('重複按訂閱不會建出第二張未付款訂單', async ({ page, request }) => {
    const { session } = await registerActivatedMember(request, 'e2e-dup')
    const auth = { headers: { Authorization: `Bearer ${session.accessToken}` } }

    // 模擬「上次按了訂閱沒付完」：先由 API 建一張未付款的訂閱訂單
    const plans = await (await request.get(`${API}/api/subscription-plans`)).json()
    const plan = plans.filter((p) => p.status === 'ACTIVE').sort((a, b) => a.price - b.price)[0]
    const created = await request.post(`${API}/api/orders/direct/subscription/checkout`, {
      ...auth, data: { subscriptionPlanId: plan.subscriptionPlanId },
    })
    expect(created.ok(), `建單失敗：${await created.text()}`).toBeTruthy()
    const firstOrderId = (await created.json()).order.orderId

    // UI 再按一次同方案的訂閱
    await page.goto('/')
    await page.evaluate((s) => {
      localStorage.setItem('auth', JSON.stringify({
        token: s.accessToken, accountId: s.accountId, email: s.email, roles: s.roles,
      }))
    }, session)
    await page.route(/ecpay\.com\.tw/, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html', body: 'ECPAY_STUB' }),
    )
    await page.goto('/subscription')
    const card = page.locator('.plan-card').filter({ hasText: plan.name }).first()
    await expect(card).toBeVisible()
    await card.getByRole('button').click()
    // 走「續付既有訂單」：拿新表單導向綠界（被 stub 攔下）
    await page.waitForURL(/ecpay\.com\.tw/)

    // 名下這個方案的未付款訂單仍只有原來那一張
    const orders = await (await request.get(`${API}/api/orders`, auth)).json()
    const unpaidSubs = orders.filter((o) => o.status === 'UNPAID' && o.title === plan.name)
    expect(unpaidSubs.map((o) => o.orderId)).toEqual([firstOrderId])
  })
})
