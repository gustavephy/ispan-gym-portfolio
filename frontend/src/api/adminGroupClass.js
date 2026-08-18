import api from './axios.instance.js'

// 管理員排定團課場次，對應後端 AdminGroupClassController 的
// POST /api/admin/group-classes、DELETE /api/admin/group-classes/{sessionId}。
// 跟團課主檔管理（adminGroupCourse.js）分開檔案，職責不同：
// 那邊管課程本身（教練、名稱、預設容量……），這裡管特定日期/教室的場次。

/**
 * 建立團課場次，需登入且為 ADMIN
 * @param {{groupCourseId: number, roomName: string, startAt: string, endAt: string, capacity?: number}} payload
 */
export const createSession = (payload) => api.post('/api/admin/group-classes', payload)

/**
 * 取消團課場次，需登入且為 ADMIN
 * DELETE 慣例上不帶 body，acknowledgeExistingBooking 用 query string 傳
 * @param {number} sessionId 要取消的場次 id
 * @param {boolean} acknowledgeExistingBooking 該場次已有會員報名時，是否二次確認要一併取消
 */
export const cancelSession = (sessionId, acknowledgeExistingBooking) =>
  api.delete(`/api/admin/group-classes/${sessionId}`, { params: { acknowledgeExistingBooking } })
