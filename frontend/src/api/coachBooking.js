import api from './axios.instance.js'

// 會員瀏覽／預約教練私教課時段，對應後端 CoachBookingController 的
// GET /api/member/trainers/{trainerId}/schedules。教練清單則走 TrainerController。
// 跟 coachSchedule.js 職責分開：那是教練管理自己的時段，這裡是會員瀏覽別人開的時段。

/**
 * 查詢公開教練清單，不需登入即可呼叫。
 * 走 TrainerController 的公開端點；回應含 UNAVAILABLE 教練，
 * 要只顯示在職教練的畫面請自行以 status === 'ACTIVE' 過濾。
 */
export const getTrainers = () => api.get('/api/trainers')

/**
 * 查詢單一公開教練的完整介紹（含 experience／bio／profileImageUrl），不需登入即可呼叫。
 * 走 TrainerController 的公開端點；已軟刪除的教練不會回傳（後端回 404）。
 * 回應 TrainerResponse：{ trainerId, displayName, specialty, experience, bio, profileImageUrl, status }
 * 不含 employeeId／email／電話等內部資料。
 * @param {number} trainerId 教練 id
 */
export const getTrainer = (trainerId) => api.get(`/api/trainers/${trainerId}`)

/**
 * 分頁查詢指定教練目前可預約（AVAILABLE）的時段，可依日期區間篩選，不需登入即可呼叫。
 * 不開放狀態篩選：這支端點固定只回傳 AVAILABLE，是設計決定。
 * @param {number} trainerId 教練 id
 * @param {{page?: number, size?: number, dateFrom?: string, dateTo?: string}} params page 從 0 開始，dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getTrainerSchedules = (trainerId, { page = 0, size = 10, dateFrom = '', dateTo = '' } = {}) =>
  api.get(`/api/member/trainers/${trainerId}/schedules`, {
    params: { page, size, dateFrom: dateFrom || undefined, dateTo: dateTo || undefined },
  })

/**
 * 預約指定時段，需登入。memberId 由後端從登入身分解析，這裡不用傳
 * @param {number} slotId 要預約的時段 id
 */
export const createBooking = (slotId) => api.post(`/api/member/schedules/${slotId}/booking`)

/**
 * 分頁查詢自己的所有預約紀錄，可依狀態與上課日期區間篩選，需登入
 * @param {{page?: number, size?: number, status?: string, dateFrom?: string, dateTo?: string}} params
 *   page 從 0 開始；status 為 PENDING/COMPLETED/CANCELLED；dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getMyBookings = ({ page = 0, size = 10, status = '', dateFrom = '', dateTo = '' } = {}) =>
  api.get('/api/member/bookings', { params: { page, size, status: status || undefined, dateFrom: dateFrom || undefined, dateTo: dateTo || undefined } })

/**
 * 取消指定的預約，需登入
 * DELETE 慣例上不帶 body，acknowledgeConsumption 用 query string 傳
 * @param {number} bookingId 要取消的預約 id
 * @param {boolean} acknowledgeConsumption 距上課不到 72 小時、取消會視同消耗額度時，是否二次確認
 */
export const cancelBooking = (bookingId, acknowledgeConsumption) =>
  api.delete(`/api/member/schedules/${bookingId}/cancel`, { params: { acknowledgeConsumption } })
