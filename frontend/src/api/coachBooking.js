import api from './axios.instance.js'

// 會員瀏覽／預約教練私教課時段，對應後端 CoachBookingController 的
// GET /api/member/trainers、GET /api/member/trainers/{trainerId}/schedules。
// 跟 coachSchedule.js 職責分開：那是教練管理自己的時段，這裡是會員瀏覽別人開的時段。

/**
 * 查詢在職教練清單，不需登入即可呼叫。
 * 改接新版 TrainerController 的公開端點，不再依賴 CoachBookingController 的
 * /api/member/trainers（該端點即將淘汰）
 */
export const getTrainers = () => api.get('/api/trainers')

/**
 * 查詢指定教練目前的所有時段（含非 AVAILABLE 的，由畫面自行篩選），不需登入即可呼叫
 * @param {number} trainerId 教練 id
 */
export const getTrainerSchedules = (trainerId) => api.get(`/api/member/trainers/${trainerId}/schedules`)

/**
 * 預約指定時段，需登入。memberId 由後端從登入身分解析，這裡不用傳
 * @param {number} slotId 要預約的時段 id
 */
export const createBooking = (slotId) => api.post(`/api/member/schedules/${slotId}/booking`)

/**
 * 分頁查詢自己的所有預約紀錄，需登入
 * @param {{page?: number, size?: number}} params page 從 0 開始
 */
export const getMyBookings = ({ page = 0, size = 10 } = {}) =>
  api.get('/api/member/bookings', { params: { page, size } })

/**
 * 取消指定的預約，需登入
 * DELETE 慣例上不帶 body，acknowledgeConsumption 用 query string 傳
 * @param {number} bookingId 要取消的預約 id
 * @param {boolean} acknowledgeConsumption 距上課不到 72 小時、取消會視同消耗額度時，是否二次確認
 */
export const cancelBooking = (bookingId, acknowledgeConsumption) =>
  api.delete(`/api/member/schedules/${bookingId}/cancel`, { params: { acknowledgeConsumption } })
