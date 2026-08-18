import api from './axios.instance.js'

// 教練排班（私教課時段）對應後端 CoachSlotController 的
// GET/POST/PUT /api/trainer/schedules。取消時段是下一步再加。

/** 查詢目前登入教練自己的所有時段（含已取消的舊紀錄，由畫面自行決定怎麼呈現） */
export const getMySlots = () => api.get('/api/trainer/schedules')

/**
 * 建立新時段
 * @param {{startAt: string, endAt: string}} payload ISO 格式時間字串，例如 "2026-08-20T10:00:00"
 */
export const createSlot = (payload) => api.post('/api/trainer/schedules', payload)

/**
 * 修改既有時段
 * @param {number} slotId 要修改的時段 id
 * @param {{startAt: string, endAt: string, acknowledgeExistingBooking: boolean}} payload
 */
export const updateSlot = (slotId, payload) => api.put(`/api/trainer/schedules/${slotId}`, payload)

/**
 * 取消既有時段
 * DELETE 慣例上不帶 body，acknowledgeExistingBooking 用 query string 傳，跟 updateSlot 用 body 傳不同
 * @param {number} slotId 要取消的時段 id
 * @param {boolean} acknowledgeExistingBooking 該時段已有會員預約時，是否二次確認要一併取消
 */
export const cancelSlot = (slotId, acknowledgeExistingBooking) =>
  api.delete(`/api/trainer/schedules/${slotId}`, { params: { acknowledgeExistingBooking } })

/**
 * 核銷一筆預約：教練輸入會員出示的核銷碼比對，比對成功即完成核銷
 * @param {number} bookingId 要核銷的預約 id（不是時段 id，對應 CoachSlotResponse 的 bookingId）
 * @param {string} checkInKey 會員出示的核銷碼
 */
export const checkInBooking = (bookingId, checkInKey) =>
  api.put(`/api/trainer/schedules/${bookingId}/checkin`, { checkInKey })
