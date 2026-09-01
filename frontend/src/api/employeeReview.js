import api from './axios.instance.js'

// 員工申請審核，對應後端 EmployeeReviewController 的 /api/employees/reviews。
// 權限為 ADMIN（2026-08-31 政策定案，由原本「任何在職員工皆可審核」收緊）。
// 路徑仍獨立於 /api/admin/** 之外——那批掛著訂單、商品等其他後台，保留獨立
// matcher 方便日後若要放寬回 hasRole('EMPLOYEE') 只改一行。

/** 待審核的員工申請清單（status=PENDING_APPROVAL），回傳純陣列不分頁，需 ADMIN */
export const getPendingEmployees = () => api.get('/api/employees/reviews/pending')

/**
 * 核准員工申請：status 轉為 ACTIVE，需 ADMIN
 * @param {number} employeeId
 * @param {string} [reviewNote] 審核備註，選填
 */
export const approveEmployee = (employeeId, reviewNote) =>
  api.post(`/api/employees/reviews/${employeeId}/approve`, { reviewNote: reviewNote || null })

/**
 * 拒絕員工申請：status 轉為 REJECTED，需 ADMIN。
 * reason 為後端 @NotBlank 必填，空白會被擋下回 400。
 * @param {number} employeeId
 * @param {string} reason 拒絕原因
 */
export const rejectEmployee = (employeeId, reason) =>
  api.post(`/api/employees/reviews/${employeeId}/reject`, { reason })
