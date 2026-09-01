import { test, expect, ACCOUNTS, loginAs } from './fixtures.js'

/**
 * 第四批「完整人員管理」E2E。
 *
 * 分兩組：
 *  - route-mock：不碰真後端。用 addInitScript 直接注入一組假 admin session（不經真登入，
 *    也就不受 /api/account/me 狀態或限流影響），未被個別 mock 的 /api/** 一律回空。
 *    覆蓋 Loading／空資料／錯誤／篩選／表單驗證／確認 Modal／角色升降＋停用恢復＋Trainer
 *    身分阻擋／Trainer 可服務狀態切換／停用預檢逐項 blockers／正式停用 409 vs 404 的不同處理／
 *    自我降級後導頁。
 *  - real backend：登入真實種子帳號打真實後端，驗證讀取路徑、四角色路由矩陣、行動版 RWD，
 *    以及 emp-trainer 的員工／教練自助資料「改了再改回」往返（可逆，不污染其他 spec）。
 *
 * 破壞性 mutation（真的改角色、真的停用/恢復）刻意只在 route-mock 組跑——在共用開發 DB
 * 上自動執行會動到其他 spec 依賴的種子帳號。
 */

// 用 regex：list 端點帶 query string，glob 的 '?' 是萬用字元語意，容易誤判
const EMP_LIST = /\/api\/admin\/employees\?/
const TRAINER_LIST = /\/api\/admin\/trainers\?/
const MOCK_ADMIN_EMAIL = 'mock-admin@local.test'

function json(body, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
function apiError(code, message, status) {
  return json({ code, message, timestamp: '2026-01-01T00:00:00' }, status)
}
function page0(content, totalElements = content.length) {
  return { content, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / 10)), number: 0, size: 10 }
}

/**
 * 注入假 admin session（roles 不含 MEMBER，購物車 store 便會直接略過 /api/cart），
 * 並讓所有未被個別 mock 的 /api/** 回空——route-mock 組完全與真後端脫鉤。
 */
// 只攔真正的後端呼叫（http://localhost:5173/api/…）——不要用 '**/api/**' glob，
// 那會連 Vite dev server 自己的 /src/api/*.js 模組請求一起攔掉，整個 app 載不起來。
const API_CALL = /^https?:\/\/localhost:5173\/api\//

async function mockAdminSession(page, roles = ['EMPLOYEE', 'ADMIN']) {
  await page.addInitScript(
    ([email, r]) => {
      localStorage.setItem(
        'auth',
        JSON.stringify({ token: 'mock.jwt.token', accountId: 999, email, roles: r }),
      )
    },
    [MOCK_ADMIN_EMAIL, roles],
  )
  await page.route(API_CALL, (route) =>
    route.fulfill(json({ items: [], content: [], totalElements: 0, totalPages: 0, totalAmount: 0 })),
  )
}

const EMP_ROW = {
  employeeId: 1,
  displayName: '王小明',
  email: 'w1@local.test',
  phone: '0912345678',
  role: 'EMPLOYEE',
  status: 'ACTIVE',
  hireDate: '2026-01-02',
  createdAt: '2026-01-02T09:00:00',
}
const EMP_DETAIL = {
  ...EMP_ROW,
  firstName: '小明',
  lastName: '王',
  isTrainer: false,
  reviewedBy: { employeeId: 9, displayName: '審核人' },
  reviewedAt: '2026-01-01T09:00:00',
  reviewNote: null,
  updatedAt: '2026-02-01T09:00:00',
}
const TRAINER_ROW = {
  trainerId: 7,
  employeeId: 3,
  displayName: '教練阿德',
  email: 't7@local.test',
  specialty: '重量訓練',
  status: 'ACTIVE',
  deletedAt: null,
  createdAt: '2026-01-02T09:00:00',
}
const noBlockers = { openSlots: 0, activeBookings: 0, activePrivateCourses: 0, upcomingGroupClassSessions: 0 }

// ─────────────────────────────  route-mock 組  ─────────────────────────────
test.describe('D4 人員管理（route-mock）', () => {
  test.beforeEach(async ({ page }) => {
    await mockAdminSession(page)
  })

  // ---- 列表狀態 ----
  test('員工列表：Loading → 有資料表格', async ({ page }) => {
    await page.route(EMP_LIST, async (route) => {
      await new Promise((r) => setTimeout(r, 300))
      await route.fulfill(json(page0([EMP_ROW])))
    })
    await page.goto('/admin/employees')
    await expect(page.locator('.spinner')).toBeVisible()
    await expect(page.getByRole('cell', { name: '王小明' })).toBeVisible()
    await expect(page.getByText('共 1 位員工')).toBeVisible()
  })

  test('員工列表：空資料顯示 EmptyState', async ({ page }) => {
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([], 0))))
    await page.goto('/admin/employees')
    await expect(page.getByText('目前沒有任何員工資料')).toBeVisible()
  })

  test('員工列表：後端 500 顯示錯誤訊息、不整頁崩掉', async ({ page }) => {
    await page.route(EMP_LIST, (route) =>
      route.fulfill(apiError('INTERNAL_ERROR', '系統發生錯誤，請稍後再試', 500)),
    )
    await page.goto('/admin/employees')
    await expect(page.locator('.operation-message--error')).toHaveText('系統發生錯誤，請稍後再試')
    await expect(page.getByRole('heading', { name: '員工管理' })).toBeVisible()
  })

  test('員工列表：狀態篩選帶入 query 並重設頁碼', async ({ page }) => {
    const seen = []
    await page.route(EMP_LIST, (route) => {
      seen.push(new URL(route.request().url()).searchParams.get('status'))
      route.fulfill(json(page0([EMP_ROW])))
    })
    await page.goto('/admin/employees')
    await expect(page.getByRole('cell', { name: '王小明' })).toBeVisible()
    await page.getByLabel('狀態').selectOption('INACTIVE')
    await expect.poll(() => seen.at(-1)).toBe('INACTIVE')
  })

  // ---- 編輯 ----
  test('員工編輯：表單驗證擋下錯誤電話', async ({ page }) => {
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.route('**/api/admin/employees/1', (route) => route.fulfill(json(EMP_DETAIL)))
    await page.goto('/admin/employees')

    await page.getByRole('button', { name: '詳細／編輯' }).click()
    await expect(page.getByRole('heading', { name: '員工詳細／編輯' })).toBeVisible()
    // 初始渲染：欄位值必須來自 API，不能是空白（曾因彈窗內 v-model.trim 初次 mount 未上值）
    await expect(page.getByLabel('電話')).toHaveValue(EMP_DETAIL.phone)
    await expect(page.getByLabel('姓氏')).toHaveValue(EMP_DETAIL.lastName)
    await expect(page.getByLabel('名字')).toHaveValue(EMP_DETAIL.firstName)
    await page.getByLabel('電話').fill('123')
    await expect(page.getByRole('button', { name: '儲存' })).toBeDisabled()
    await page.getByLabel('電話').fill('0912345678')
    await expect(page.getByRole('button', { name: '儲存' })).toBeEnabled()
  })

  test('員工編輯：儲存 → 確認彈窗列出前後值 → 確認修改後送 PUT 回列表', async ({ page }) => {
    let putBody = null
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.route('**/api/admin/employees/1', (route) => {
      if (route.request().method() === 'PUT') {
        putBody = route.request().postDataJSON()
        return route.fulfill(json({ ...EMP_DETAIL, phone: putBody.phone }))
      }
      return route.fulfill(json(EMP_DETAIL))
    })
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '詳細／編輯' }).click()
    await page.getByLabel('電話').fill('0955555555')
    await page.getByRole('button', { name: '儲存' }).click()

    // 先跳「確認修改員工資料」彈窗、列出電話前→後，尚未送 PUT
    await expect(page.getByRole('heading', { name: '確認修改員工資料' })).toBeVisible()
    await expect(page.locator('.change-list').getByText('0912345678')).toBeVisible()
    await expect(page.locator('.change-list').getByText('0955555555')).toBeVisible()
    expect(putBody).toBeNull()

    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('員工資料已更新')).toBeVisible()
    expect(putBody).toMatchObject({ phone: '0955555555', firstName: '小明', lastName: '王' })
  })

  test('員工編輯確認彈窗：沒有變更時不開彈窗、不送 API', async ({ page }) => {
    let putCalled = false
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.route('**/api/admin/employees/1', (route) => {
      if (route.request().method() === 'PUT') putCalled = true
      return route.fulfill(json(EMP_DETAIL))
    })
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '詳細／編輯' }).click()
    await page.getByRole('button', { name: '儲存' }).click()
    await expect(page.locator('.operation-message--error')).toContainText('目前沒有需要儲存的變更')
    await expect(page.getByRole('heading', { name: '確認修改員工資料' })).toBeHidden()
    expect(putCalled).toBe(false)
  })

  // ---- 角色調整 ----
  test('角色調整：確認框顯示前後角色與後果說明', async ({ page }) => {
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '設為管理員' }).click()
    await expect(page.getByRole('heading', { name: '調整員工角色' })).toBeVisible()
    await expect(page.getByText('管理員可使用所有後台管理功能')).toBeVisible()
  })

  test('角色調整：升為管理員成功，發出正確 payload 並回列表', async ({ page }) => {
    let roleBody = null
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.route('**/api/admin/employees/1/role', (route) => {
      roleBody = route.request().postDataJSON()
      route.fulfill(json({ ...EMP_DETAIL, role: 'ADMIN' }))
    })
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '設為管理員' }).click()
    await page.getByRole('button', { name: '確定調整為管理員' }).click()
    await expect(page.getByText('已將 王小明 調整為管理員')).toBeVisible()
    expect(roleBody).toEqual({ role: 'ADMIN' })
  })

  test('角色調整：最後一位 ADMIN 的 409 就地顯示', async ({ page }) => {
    const admin = { ...EMP_ROW, employeeId: 2, displayName: '唯一管理員', role: 'ADMIN' }
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([admin]))))
    await page.route('**/api/admin/employees/2/role', (route) =>
      route.fulfill(apiError('BUSINESS_RULE_VIOLATION', '必須至少保留一位有效管理員，無法降級最後一位', 409)),
    )
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '設為一般員工' }).click()
    await page.getByRole('button', { name: /確定調整為一般員工/ }).click()
    await expect(page.getByText('必須至少保留一位有效管理員，無法降級最後一位')).toBeVisible()
  })

  test('自我降級：同步 /api/account/me 後失去 ADMIN → 導回首頁、不再抓列表', async ({ page }) => {
    const selfAdmin = { ...EMP_ROW, employeeId: 5, displayName: '我自己', email: MOCK_ADMIN_EMAIL, role: 'ADMIN' }
    let listCalls = 0
    await page.route(EMP_LIST, (route) => {
      listCalls += 1
      route.fulfill(json(page0([selfAdmin])))
    })
    await page.route('**/api/admin/employees/5/role', (route) =>
      route.fulfill(json({ ...EMP_DETAIL, employeeId: 5, role: 'EMPLOYEE' })),
    )
    // 降級後 /api/account/me 回不含 ADMIN 的 roles
    await page.route('**/api/account/me', (route) =>
      route.fulfill(json({ accountId: 999, email: MOCK_ADMIN_EMAIL, roles: ['EMPLOYEE'], createdAt: '2026-01-01T00:00:00' })),
    )
    await page.goto('/admin/employees')
    await expect(page.getByRole('cell', { name: '我自己' })).toBeVisible()
    const callsBefore = listCalls

    await page.getByRole('button', { name: '設為一般員工' }).click()
    await page.getByRole('button', { name: '確定調整為一般員工' }).click()

    await expect(page).toHaveURL('http://localhost:5173/')
    // 導離後不應再對 ADMIN 列表發請求
    await page.waitForTimeout(300)
    expect(listCalls).toBe(callsBefore)
  })

  // ---- 停用 / 恢復 ----
  test('員工停用：確認框後果說明 + 成功回列表', async ({ page }) => {
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([EMP_ROW]))))
    await page.route('**/api/admin/employees/1/suspend', (route) => route.fulfill(json({}, 200)))
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '停用', exact: true }).click()
    await expect(page.getByRole('heading', { name: '停用員工' })).toBeVisible()
    await expect(page.getByText('停用後該員工將無法登入使用任何員工功能')).toBeVisible()
    await page.getByRole('button', { name: '確定停用' }).click()
    await expect(page.getByText('已停用 王小明')).toBeVisible()
  })

  test('員工停用：具有效教練身分回 409 就地顯示', async ({ page }) => {
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([{ ...EMP_ROW }]))))
    await page.route('**/api/admin/employees/1/suspend', (route) =>
      route.fulfill(
        apiError('BUSINESS_RULE_VIOLATION', '此員工仍具有效教練身分，須待 Trainer 管理階段釋出教練身分後才能停用', 409),
      ),
    )
    await page.goto('/admin/employees')
    await page.getByRole('button', { name: '停用', exact: true }).click()
    await page.getByRole('button', { name: '確定停用' }).click()
    await expect(page.getByText('此員工仍具有效教練身分')).toBeVisible()
  })

  test('員工恢復：INACTIVE 列顯示恢復鈕，成功回列表', async ({ page }) => {
    const inactive = { ...EMP_ROW, employeeId: 6, displayName: '離職甲', status: 'INACTIVE' }
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0([inactive]))))
    await page.route('**/api/admin/employees/6/activate', (route) => route.fulfill(json({}, 200)))
    await page.goto('/admin/employees')
    await expect(page.getByRole('button', { name: '停用', exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: '恢復', exact: true }).click()
    await expect(page.getByRole('heading', { name: '恢復員工' })).toBeVisible()
    await page.getByRole('button', { name: '確定恢復' }).click()
    await expect(page.getByText('已恢復 離職甲')).toBeVisible()
  })

  test('員工 PENDING/REJECTED 列：只出詳細鈕、給審核頁提示', async ({ page }) => {
    const rows = [
      { ...EMP_ROW, employeeId: 7, displayName: '待審甲', status: 'PENDING_APPROVAL' },
      { ...EMP_ROW, employeeId: 8, displayName: '被拒乙', status: 'REJECTED' },
    ]
    await page.route(EMP_LIST, (route) => route.fulfill(json(page0(rows))))
    await page.goto('/admin/employees')
    await expect(page.getByText('審核流程')).toHaveCount(2)
    await expect(page.getByRole('button', { name: '停用', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /設為/ })).toHaveCount(0)
  })

  // ---- Trainer ----
  test('Trainer 可服務狀態切換：ACTIVE → UNAVAILABLE 成功', async ({ page }) => {
    let statusBody = null
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7/status', (route) => {
      statusBody = route.request().postDataJSON()
      route.fulfill(json({ ...TRAINER_ROW, status: 'UNAVAILABLE' }))
    })
    await page.goto('/admin/trainers')
    await page.locator('.data-table').getByRole('button', { name: '設為暫停服務' }).click()
    await expect(page.getByRole('heading', { name: '切換可服務狀態' })).toBeVisible()
    await page.locator('.app-modal-footer').getByRole('button', { name: '設為暫停服務' }).click()
    await expect(page.getByText('已將 教練阿德 設為暫停服務')).toBeVisible()
    expect(statusBody).toEqual({ status: 'UNAVAILABLE' })
  })

  test('Trainer 詳細/編輯：儲存 → 確認彈窗列出前後值 → 確認修改送 PUT', async ({ page }) => {
    let putBody = null
    const detail = { ...TRAINER_ROW, experience: '5 年資歷', bio: '嗨大家好', profileImageUrl: '', employeeStatus: 'ACTIVE' }
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7', (route) => {
      if (route.request().method() === 'PUT') {
        putBody = route.request().postDataJSON()
        return route.fulfill(json({ ...detail, specialty: putBody.specialty }))
      }
      return route.fulfill(json(detail))
    })
    await page.goto('/admin/trainers')
    await page.getByRole('button', { name: '詳細／編輯' }).click()
    await expect(page.getByRole('heading', { name: '教練詳細／編輯' })).toBeVisible()
    // 初始渲染：欄位值必須來自 API，不能是空白（比照 AdminEmployeeView 的彈窗初值回歸）
    await expect(page.getByLabel('專長')).toHaveValue(detail.specialty)
    await expect(page.getByLabel('經歷')).toHaveValue(detail.experience)
    await expect(page.getByLabel('自我介紹')).toHaveValue(detail.bio)
    await expect(page.getByLabel('個人圖片網址（選填）')).toHaveValue(detail.profileImageUrl)
    await page.getByLabel('專長').fill('核心訓練')
    await page.locator('.app-modal-footer').getByRole('button', { name: '儲存' }).click()

    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeVisible()
    await expect(page.locator('.change-list').getByText('核心訓練')).toBeVisible()
    expect(putBody).toBeNull()
    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('教練資料已更新')).toBeVisible()
    expect(putBody).toMatchObject({ specialty: '核心訓練' })
  })

  test('Trainer 停用預檢：逐項 blockers 顯示，有阻擋時禁用「確定停用」', async ({ page }) => {
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7/suspension-check', (route) =>
      route.fulfill(
        json({
          canSuspend: false,
          blockers: { openSlots: 2, activeBookings: 0, activePrivateCourses: 1, upcomingGroupClassSessions: 0 },
        }),
      ),
    )
    await page.goto('/admin/trainers')
    await page.getByRole('button', { name: '停用', exact: true }).click()

    await expect(page.getByText('未完成的排班時段')).toBeVisible()
    await expect(page.getByText('上架中的私教課方案')).toBeVisible()
    await expect(page.getByText('進行中的私教預約')).toBeVisible()
    await expect(page.getByText('即將到來的團課場次')).toBeVisible()
    await expect(page.getByText('上述有數量的項目需先於各對應模組結清')).toBeVisible()
    await expect(page.getByRole('button', { name: '確定停用' })).toBeDisabled()
  })

  test('Trainer 停用：無阻擋 → 顯示後果、確定 → 204 成功', async ({ page }) => {
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7/suspension-check', (route) =>
      route.fulfill(json({ canSuspend: true, blockers: noBlockers })),
    )
    await page.route('**/api/admin/trainers/7/suspend', (route) =>
      route.fulfill({ status: 204, body: '' }),
    )
    await page.goto('/admin/trainers')
    await page.getByRole('button', { name: '停用', exact: true }).click()
    await expect(page.getByText('教練身分（ROLE_TRAINER）會失效')).toBeVisible()
    await expect(page.getByRole('button', { name: '確定停用' })).toBeEnabled()
    await page.getByRole('button', { name: '確定停用' }).click()
    await expect(page.getByText('已停用教練 教練阿德')).toBeVisible()
  })

  test('Trainer 停用：正式停用回 409 → 重新預檢逐項呈現', async ({ page }) => {
    let checkCall = 0
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7/suspension-check', (route) => {
      checkCall += 1
      const blockers = checkCall === 1 ? noBlockers : { ...noBlockers, openSlots: 1 }
      return route.fulfill(json({ canSuspend: checkCall === 1, blockers }))
    })
    await page.route('**/api/admin/trainers/7/suspend', (route) =>
      route.fulfill(
        apiError('BUSINESS_RULE_VIOLATION', '教練仍有未完成業務，無法停用（排班 1 筆、預約 0 筆、私教課 0 筆、團課場次 0 筆）', 409),
      ),
    )
    await page.goto('/admin/trainers')
    await page.getByRole('button', { name: '停用', exact: true }).click()
    await expect(page.getByRole('button', { name: '確定停用' })).toBeEnabled()
    await page.getByRole('button', { name: '確定停用' }).click()
    await expect(page.getByText(/教練仍有未完成業務，無法停用/)).toBeVisible()
    await expect(page.getByRole('button', { name: '確定停用' })).toBeDisabled()
    expect(checkCall).toBe(2)
  })

  test('Trainer 停用：正式停用回 404（已被別人停用）→ 不重跑預檢', async ({ page }) => {
    let checkCall = 0
    await page.route(TRAINER_LIST, (route) => route.fulfill(json(page0([TRAINER_ROW]))))
    await page.route('**/api/admin/trainers/7/suspension-check', (route) => {
      checkCall += 1
      return route.fulfill(json({ canSuspend: true, blockers: noBlockers }))
    })
    await page.route('**/api/admin/trainers/7/suspend', (route) =>
      route.fulfill(apiError('NOT_FOUND', '找不到此教練', 404)),
    )
    await page.goto('/admin/trainers')
    await page.getByRole('button', { name: '停用', exact: true }).click()
    await expect(page.getByRole('button', { name: '確定停用' })).toBeEnabled()
    await page.getByRole('button', { name: '確定停用' }).click()
    await expect(page.locator('.operation-message--error')).toContainText('找不到此教練')
    // 404 不觸發重新預檢，checkCall 停在 1
    await page.waitForTimeout(500)
    expect(checkCall).toBe(1)
  })

  test('已停用教練：範圍切「已停用」→ 顯示恢復鈕，恢復成功回 UNAVAILABLE', async ({ page }) => {
    const deleted = {
      trainerId: 9,
      employeeId: 5,
      displayName: '教練阿強',
      email: 't9@local.test',
      specialty: '拳擊',
      status: 'UNAVAILABLE',
      deletedAt: '2026-02-01T09:00:00',
      createdAt: '2026-01-02T09:00:00',
    }
    await page.route(TRAINER_LIST, (route) => {
      const wantDeleted = new URL(route.request().url()).searchParams.get('deleted') === 'true'
      return route.fulfill(json(page0(wantDeleted ? [deleted] : [], wantDeleted ? 1 : 0)))
    })
    await page.route('**/api/admin/trainers/9/restore', (route) =>
      route.fulfill(json({ ...deleted, deletedAt: null, status: 'UNAVAILABLE' })),
    )
    await page.goto('/admin/trainers')
    await page.getByLabel('範圍').selectOption('true')

    const row = page.locator('.data-table tbody tr').filter({ hasText: 't9@local.test' })
    await expect(row).toBeVisible()
    await expect(row.getByText('已停用')).toBeVisible()
    await row.getByRole('button', { name: '恢復', exact: true }).click()

    await expect(page.getByRole('heading', { name: '恢復教練' })).toBeVisible()
    await expect(page.getByText(/狀態固定為「暫停服務」/)).toBeVisible()
    await page.getByRole('button', { name: '確定恢復' }).click()
    await expect(page.getByText('已恢復教練 教練阿強（狀態為暫停服務）')).toBeVisible()
  })

  test('恢復教練：所屬員工非在職回 409 就地顯示', async ({ page }) => {
    const deleted = {
      trainerId: 10,
      employeeId: 6,
      displayName: '教練阿明',
      email: 't10@local.test',
      specialty: '拳擊',
      status: 'UNAVAILABLE',
      deletedAt: '2026-02-01T09:00:00',
      createdAt: '2026-01-02T09:00:00',
    }
    await page.route(TRAINER_LIST, (route) => {
      const wantDeleted = new URL(route.request().url()).searchParams.get('deleted') === 'true'
      return route.fulfill(json(page0(wantDeleted ? [deleted] : [], wantDeleted ? 1 : 0)))
    })
    await page.route('**/api/admin/trainers/10/restore', (route) =>
      route.fulfill(apiError('BUSINESS_RULE_VIOLATION', '此教練所屬員工目前不是在職狀態，無法恢復', 409)),
    )
    await page.goto('/admin/trainers')
    await page.getByLabel('範圍').selectOption('true')
    await page.locator('.data-table tbody tr').filter({ hasText: 't10@local.test' }).getByRole('button', { name: '恢復', exact: true }).click()
    await page.getByRole('button', { name: '確定恢復' }).click()
    await expect(page.getByText('此教練所屬員工目前不是在職狀態，無法恢復')).toBeVisible()
  })
})

// ────────────────  員工申請／重新申請：手機格式前端驗證（匿名，不打真後端）  ────────────────
test.describe('D4 員工申請頁手機格式驗證', () => {
  const BAD = '0812345678' // 非 09 開頭
  const GOOD = '0912345678'
  const ERR = '請輸入正確的台灣手機號碼（09 開頭，共 10 碼）'

  for (const [label, path, id] of [
    ['員工申請', '/employee-register', 'emp-reg-phone'],
    ['重新申請', '/employee-reapply', 'emp-reapply-phone'],
  ]) {
    test(`${label}頁：非法手機顯示 inline 錯誤、合法則消失`, async ({ page }) => {
      await page.goto(path)
      const phone = page.locator(`#${id}`)
      await phone.fill(BAD)
      await expect(page.getByText(ERR)).toBeVisible()
      // maxlength=10：貼超過 10 碼會被截斷
      await phone.fill('')
      await phone.pressSequentially('091234567890')
      await expect(phone).toHaveValue('0912345678')
      await phone.fill(GOOD)
      await expect(page.getByText(ERR)).toBeHidden()
    })
  }
})

// ─────────────────────────────  real backend 組  ─────────────────────────────
test.describe('D4 人員管理（真實後端）', () => {
  test('管理員可開啟員工管理與教練管理列表', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)

    await page.goto('/admin/employees')
    await expect(page.getByRole('heading', { name: '員工管理' })).toBeVisible()
    await expect(page.locator('.data-table tbody tr').first()).toBeVisible()

    await page.goto('/admin/trainers')
    await expect(page.getByRole('heading', { name: '教練管理' })).toBeVisible()
    await expect(page.locator('.data-table tbody tr').first()).toBeVisible()
  })

  test('教練停用預檢打真實後端，四項阻擋皆渲染', async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/trainers')
    await page.getByPlaceholder('姓名／Email／專長').fill('emp-trainer@local.test')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(1)

    await page.getByRole('button', { name: '停用', exact: true }).click()
    await expect(page.getByText('停用前跨模組檢查：')).toBeVisible()
    for (const label of ['未完成的排班時段', '進行中的私教預約', '上架中的私教課方案', '即將到來的團課場次']) {
      await expect(page.getByText(label)).toBeVisible()
    }
    await page.getByRole('button', { name: '取消' }).click()
  })

  test('員工本人可修改電話：儲存 → 確認彈窗 → 確認修改（改了再改回）', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer) // emp-trainer：具員工身分
    await page.goto('/employee/profile')
    await expect(page.getByRole('heading', { name: '員工基本資料' })).toBeVisible()

    const phone = page.getByLabel('聯絡電話')
    const original = await phone.inputValue()
    const temp = original === '0900000000' ? '0900000001' : '0900000000'

    await phone.fill(temp)
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改基本資料' })).toBeVisible()
    await expect(page.locator('.change-list').getByText(temp)).toBeVisible()
    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('基本資料已更新')).toBeVisible()

    await phone.fill(original)
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改基本資料' })).toBeVisible()
    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('基本資料已更新')).toBeVisible()
  })

  test('員工電話格式驗證：非台灣手機號碼擋下、不可送出', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/employee/profile')
    const phone = page.getByLabel('聯絡電話')
    const original = await phone.inputValue()

    await phone.fill('0812345678') // 非 09 開頭
    await expect(page.getByText('請輸入正確的台灣手機號碼（09 開頭，共 10 碼）')).toBeVisible()
    await expect(page.getByRole('button', { name: '儲存變更' })).toBeDisabled()

    await phone.fill(original)
    await expect(page.getByRole('button', { name: '儲存變更' })).toBeEnabled()
  })

  test('教練本人可修改專業資料：儲存 → 確認彈窗 → 確認修改（改了再改回）', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/trainer/profile')
    await expect(page.getByRole('heading', { name: '教練資料' })).toBeVisible()

    const specialty = page.getByLabel('專長')
    const original = await specialty.inputValue()
    const temp = `${original} (E2E ${Date.now()})`.slice(0, 200)

    // 「儲存變更」不直接送出，先跳「確認修改教練資料」彈窗列出前→後
    await specialty.fill(temp)
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeVisible()
    // 彈窗列出「修改後」值（original 是它的前綴，會同時命中修改前/後兩格，故只驗唯一的 temp）
    await expect(page.locator('.change-list').getByText(temp)).toBeVisible()
    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('教練資料已更新')).toBeVisible()

    // 改回原值
    await specialty.fill(original)
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeVisible()
    await page.getByRole('button', { name: '確認修改' }).click()
    await expect(page.getByText('教練資料已更新')).toBeVisible()
  })

  test('教練自助確認彈窗：按「返回編輯」不送出、資料不變', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/trainer/profile')
    const specialty = page.getByLabel('專長')
    const original = await specialty.inputValue()

    await specialty.fill(`${original}_暫改`)
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeVisible()
    await page.getByRole('button', { name: '返回編輯' }).click()
    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeHidden()

    // 重新載入 → 後端資料仍是原值（沒被送出）
    await page.reload()
    await expect(page.getByLabel('專長')).toHaveValue(original)
  })

  test('四角色路由矩陣：各身分只進得去自己該進的頁', async ({ page }) => {
    const HOME = 'http://localhost:5173/'
    const ADMIN_HOME = 'http://localhost:5173/admin/reports'
    const TRAINER_HOME = 'http://localhost:5173/trainer/schedules'

    /**
     * 被路由守衛擋下時，守衛先 `return '/'`，接著 router 依角色把首頁再導向：
     * ADMIN → /admin/reports、TRAINER → /trainer/schedules、純 MEMBER → 留在 /。
     * （見 router/index.js 的 beforeEach，這是 upstream 的角色首頁導向。）
     */
    async function expectDenied(path, roleHome, label) {
      await page.goto(path)
      await expect(page, label).toHaveURL(roleHome)
    }

    // 純會員：四頁都不得進入，被導回首頁（會員沒有角色首頁，留在 /）
    await loginAs(page, ACCOUNTS.member)
    for (const path of ['/admin/employees', '/admin/trainers', '/employee/profile', '/trainer/profile']) {
      await expectDenied(path, HOME, `member 不應進入 ${path}`)
    }

    // 會員＋管理員：admin 頁與員工自助可進；教練自助被拒 → 落在 ADMIN 首頁
    await loginAs(page, ACCOUNTS.admin)
    await page.goto('/admin/employees')
    await expect(page.getByRole('heading', { name: '員工管理' })).toBeVisible()
    await page.goto('/admin/trainers')
    await expect(page.getByRole('heading', { name: '教練管理' })).toBeVisible()
    await page.goto('/employee/profile')
    await expect(page.getByRole('heading', { name: '員工基本資料' })).toBeVisible()
    await page.goto('/admin/employee-reviews')
    await expect(page.getByRole('heading', { name: '員工申請審核' })).toBeVisible()
    await expectDenied('/trainer/profile', ADMIN_HOME, 'admin 不應進入教練自助頁')

    // 會員＋員工＋教練（非管理員）：兩個自助頁可進；admin 頁與員工審核被拒 → 落在 TRAINER 首頁
    await loginAs(page, ACCOUNTS.trainer)
    await page.goto('/employee/profile')
    await expect(page.getByRole('heading', { name: '員工基本資料' })).toBeVisible()
    await page.goto('/trainer/profile')
    await expect(page.getByRole('heading', { name: '教練資料' })).toBeVisible()
    await expectDenied('/admin/employees', TRAINER_HOME, '純教練不應進入 admin 員工管理')
    await expectDenied('/admin/trainers', TRAINER_HOME, '純教練不應進入 admin 教練管理')
    await expectDenied('/admin/employee-reviews', TRAINER_HOME, '員工審核已改 ADMIN-only，純教練不得進入')
  })

  test('行動版：員工管理表格橫向捲動、body 不溢出', async ({ page }) => {
    // loginAs 會斷言 NavBar 的 email 可見，但行動版 NavBar 會隱藏 email，
    // 因此先在桌面尺寸登入，再切到行動版尺寸
    await loginAs(page, ACCOUNTS.admin)
    await page.setViewportSize({ width: 375, height: 812 })
    await page.goto('/admin/employees')
    await expect(page.getByRole('heading', { name: '員工管理' })).toBeVisible()
    await expect(page.locator('.data-table tbody tr').first()).toBeVisible()

    const overflow = await page.evaluate(() => {
      const doc = document.documentElement
      return { bodyOverflow: doc.scrollWidth - doc.clientWidth, tableScrolls: !!document.querySelector('.table-card') }
    })
    expect(overflow.tableScrolls).toBe(true)
    // 版面沒有整頁水平捲動（容許 1px 誤差）
    expect(overflow.bodyOverflow).toBeLessThanOrEqual(1)
  })

  test('行動版：教練自助資料頁與確認彈窗不水平溢出', async ({ page }) => {
    await loginAs(page, ACCOUNTS.trainer)
    await page.setViewportSize({ width: 375, height: 812 })
    await page.goto('/trainer/profile')
    await expect(page.getByRole('heading', { name: '教練資料' })).toBeVisible()

    const noOverflow = () =>
      page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(await noOverflow()).toBeLessThanOrEqual(1)

    // 開確認彈窗（長文字的「修改前 → 修改後」在窄螢幕改為直排）
    const specialty = page.getByLabel('專長')
    const original = await specialty.inputValue()
    await specialty.fill(`${original}｜行動版測試很長很長的一段文字內容`.slice(0, 200))
    await page.getByRole('button', { name: '儲存變更' }).click()
    await expect(page.getByRole('heading', { name: '確認修改教練資料' })).toBeVisible()
    expect(await noOverflow()).toBeLessThanOrEqual(1)
    // 不送出、改回原值
    await page.getByRole('button', { name: '返回編輯' }).click()
    await specialty.fill(original)
  })
})
