import api from './axios.instance.js'

// 管理員維護員工資料，對應後端 AdminEmployeeController 的 /api/admin/employees（皆需 ADMIN）。
// 跟員工自助的 employeeProfile.js 職責分開：那是員工改自己（只有電話），這裡是管理員代改別人
// 並管理角色與在職狀態。不含員工申請／同儕審核（employeeReview.js，權限模型不同）。

/**
 * 分頁查詢員工，需 ADMIN。回應是 Spring 的 Page 結構（content／totalElements／totalPages），
 * 不是專案自訂的 PageResponse。空字串一律轉 undefined，否則後端 enum 轉換會回 400。
 * @param {{keyword?: string, status?: string, role?: string, page?: number, size?: number,
 *          sort?: string, direction?: string}} params
 *   keyword 不分大小寫比對 姓名／手機／登入 email；status 為 EmployeeStatus；role 為 EmployeeRole；
 *   page 從 0 開始；size 上限 100；sort 僅接受 id／name／hireDate／createdAt（其餘退回 id）
 */
export const getEmployees = ({
  keyword = '',
  status = '',
  role = '',
  page = 0,
  size = 10,
  sort = '',
  direction = '',
} = {}) =>
  api.get('/api/admin/employees', {
    params: {
      keyword: keyword || undefined,
      status: status || undefined,
      role: role || undefined,
      page,
      size,
      sort: sort || undefined,
      direction: direction || undefined,
    },
  })

/**
 * 查單一員工的完整資料（含姓名、審核備註等，列表刻意不回傳），需 ADMIN
 * @param {number} employeeId
 */
export const getEmployeeById = (employeeId) => api.get(`/api/admin/employees/${employeeId}`)

/**
 * 管理員代改員工資料，需 ADMIN。PUT 為完整覆蓋語意，四欄皆必填。
 * 不含 role／status——兩者走各自獨立端點。
 * @param {number} employeeId
 * @param {{firstName: string, lastName: string, phone: string, hireDate: string}} payload
 *   hireDate 為 yyyy-MM-dd
 */
export const updateEmployee = (employeeId, payload) =>
  api.put(`/api/admin/employees/${employeeId}`, payload)

/**
 * 調整員工角色（EMPLOYEE⇄ADMIN），需 ADMIN。把角色設為指定值。
 * 同角色回 409；目標非 ACTIVE 回 409；降級最後一位有效 ADMIN 回 409。
 * @param {number} employeeId
 * @param {'EMPLOYEE'|'ADMIN'} role
 */
export const changeEmployeeRole = (employeeId, role) =>
  api.post(`/api/admin/employees/${employeeId}/role`, { role })

/**
 * 停用員工（ACTIVE → INACTIVE），需 ADMIN。回 200 無 body。
 * 具有效 Trainer 身分、或為最後一位有效 ADMIN、或狀態非 ACTIVE 時回 409。
 * @param {number} employeeId
 */
export const suspendEmployee = (employeeId) =>
  api.post(`/api/admin/employees/${employeeId}/suspend`)

/**
 * 恢復員工（INACTIVE → ACTIVE），需 ADMIN。回 200 無 body。
 * 非 INACTIVE（含 PENDING_APPROVAL／REJECTED，須走審核流程）時回 409。
 * @param {number} employeeId
 */
export const activateEmployee = (employeeId) =>
  api.post(`/api/admin/employees/${employeeId}/activate`)
