import api from './axios.instance.js'

// 管理端查詢教練資料與排課情況，對應後端 AdminTrainerController 的 /api/admin/trainers。
// 排課查詢是唯讀，跟教練自助端 @/api/coachSchedule.js 的建立/修改/取消完全無關。

/**
 * 分頁查詢教練，需登入且為 ADMIN。
 * 回應是 Spring 的 Page 結構（content／totalElements／totalPages）。
 * @param {{keyword?: string, status?: string, page?: number, size?: number,
 *          sort?: string, direction?: string}} params
 *   keyword 不分大小寫比對姓名／Email／專長；status 省略代表全部；page 從 0 開始
 */
export const getTrainers = ({
  keyword = '',
  status = '',
  page = 0,
  size = 10,
  sort = '',
  direction = '',
} = {}) =>
  api.get('/api/admin/trainers', {
    params: {
      keyword: keyword || undefined,
      status: status || undefined,
      page,
      size,
      sort: sort || undefined,
      direction: direction || undefined,
    },
  })

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
