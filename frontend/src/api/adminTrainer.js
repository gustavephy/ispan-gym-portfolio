import api from './axios.instance.js'

// 管理端查詢教練資料與排課情況，對應後端 AdminTrainerController 的 /api/admin/trainers。
// 排課查詢是唯讀，跟教練自助端 @/api/coachSchedule.js 的建立/修改/取消完全無關。

/**
 * 分頁查詢教練，需登入且為 ADMIN。
 * 回應是 Spring 的 Page 結構（content／totalElements／totalPages）。
 * @param {{keyword?: string, status?: string, deleted?: boolean, page?: number, size?: number,
 *          sort?: string, direction?: string}} params
 *   keyword 不分大小寫比對姓名／Email／專長；status 省略代表全部；
 *   deleted 預設 false（只看未停用），true 只看已停用（軟刪除）；page 從 0 開始；
 *   sort 白名單 trainerId／name／status／createdAt／updatedAt（非法值退回 trainerId）
 */
export const getTrainers = ({
  keyword = '',
  status = '',
  deleted = false,
  page = 0,
  size = 10,
  sort = '',
  direction = '',
} = {}) =>
  api.get('/api/admin/trainers', {
    params: {
      keyword: keyword || undefined,
      status: status || undefined,
      deleted,
      page,
      size,
      sort: sort || undefined,
      direction: direction || undefined,
    },
  })

/**
 * 查單一教練的完整資料（含 experience／bio／profileImageUrl／employeeStatus／updatedAt），需 ADMIN。
 * 不過濾 deletedAt——ADMIN 可查已停用的教練。
 * @param {number} trainerId
 */
export const getTrainerById = (trainerId) => api.get(`/api/admin/trainers/${trainerId}`)

/**
 * 管理員代改教練專業資料，需 ADMIN。員工姓名／電話等一般個資改走 adminEmployee.js。
 * @param {number} trainerId
 * @param {{specialty: string, experience: string, bio: string, profileImageUrl?: string}} payload
 */
export const updateTrainer = (trainerId, payload) =>
  api.put(`/api/admin/trainers/${trainerId}`, payload)

/**
 * 切換教練「可服務狀態」（ACTIVE⇄UNAVAILABLE），需 ADMIN。
 * 已軟刪除者回 404（文件寫 409，實際 changeStatus 走 lockOrThrow 回 404）；
 * 狀態與現況相同回 409；欲設為 ACTIVE 但底下員工非 ACTIVE 回 409。
 * @param {number} trainerId
 * @param {'ACTIVE'|'UNAVAILABLE'} status
 */
export const changeTrainerStatus = (trainerId, status) =>
  api.post(`/api/admin/trainers/${trainerId}/status`, { status })

/**
 * 停用預檢，需 ADMIN。只供畫面提示，正式停用仍會在交易內重新檢查，不可信任這裡的結果已過期。
 * 回應 TrainerSuspensionCheckResponse：
 *   { canSuspend: boolean, blockers: { openSlots, activeBookings, activePrivateCourses, upcomingGroupClassSessions } }
 * @param {number} trainerId
 */
export const checkTrainerSuspension = (trainerId) =>
  api.get(`/api/admin/trainers/${trainerId}/suspension-check`)

/**
 * 正式停用教練（軟刪除，deletedAt = now），需 ADMIN。成功回 204 無 body。
 * 有任一未完成業務時回 409，訊息內含四項數量（純字串，非結構化——呼叫端不要解析，
 * 改為重呼 checkTrainerSuspension 取得最新結構化 blockers 再逐項呈現）。
 * @param {number} trainerId
 */
export const suspendTrainer = (trainerId) => api.post(`/api/admin/trainers/${trainerId}/suspend`)

/**
 * 恢復已軟刪除的教練，需 ADMIN。回應詳細資料，恢復後固定 status = UNAVAILABLE
 * （即使停用前是 ACTIVE，也不自動跳回，須 ADMIN 另外切換）。
 * 未停用者回 409；底下員工非 ACTIVE／已軟刪除回 409。
 * @param {number} trainerId
 */
export const restoreTrainer = (trainerId) => api.post(`/api/admin/trainers/${trainerId}/restore`)

/**
 * 分頁查詢指定教練的排課情況（時段與其預約狀態、預約會員），唯讀。
 * @param {number} trainerId 要查詢的教練 id
 * @param {{page?: number, size?: number, status?: string, dateFrom?: string, dateTo?: string}} params
 *   page 從 0 開始；status 為 AVAILABLE/PENDING/COMPLETED/CANCELLED（語意見後端
 *   CoachSlotRepository#searchMySlots：混合時段狀態與預約狀態的四種畫面顯示狀態，
 *   不是 SlotStatus 本身的三個值）；dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getTrainerCoachSlots = (
  trainerId,
  { page = 0, size = 10, status = '', dateFrom = '', dateTo = '' } = {},
) =>
  api.get(`/api/admin/trainers/${trainerId}/coach-slots`, {
    params: {
      page,
      size,
      status: status || undefined,
      dateFrom: dateFrom || undefined,
      dateTo: dateTo || undefined,
    },
  })
