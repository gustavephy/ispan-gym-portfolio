import api from './axios.instance.js'

// 員工申請審核，對應後端 EmployeeReviewController 的 /api/employees/reviews。
// 權限是 EMPLOYEE 而非 ADMIN——任何在職員工皆可審核（同儕審核），
// 跟 /api/admin/** 那批管理者專屬後台是不同的權限模型。

/** 待審核的員工申請清單（status=PENDING_APPROVAL），回傳純陣列不分頁，需登入且為 EMPLOYEE */
export const getPendingEmployees = () => api.get('/api/employees/reviews/pending')

/**
 * 核准員工申請：status 轉為 ACTIVE，需 EMPLOYEE
 * @param {number} employeeId
 * @param {string} [reviewNote] 審核備註，選填
 */
export const approveEmployee = (employeeId, reviewNote) =>
  api.post(`/api/employees/reviews/${employeeId}/approve`, { reviewNote: reviewNote || null })

/**
 * 拒絕員工申請：status 轉為 REJECTED，需 EMPLOYEE。
 * reason 為後端 @NotBlank 必填，空白會被擋下回 400。
 * @param {number} employeeId
 * @param {string} reason 拒絕原因
 */
export const rejectEmployee = (employeeId, reason) =>
  api.post(`/api/employees/reviews/${employeeId}/reject`, { reason })
