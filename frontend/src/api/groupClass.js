import api from './axios.instance.js'

// 會員瀏覽／報名團體課程場次，對應後端 GroupClassController 的
// GET /api/member/group-classes、POST /api/member/group-classes/{sessionId}/booking。

/**
 * 分頁查詢上架中（status=ACTIVE）的團課主檔，可依課程名稱或教練姓名搜尋，
 * 不需登入即可呼叫，對應 GroupCourseController
 * @param {{page?: number, size?: number, search?: string}} params page 從 0 開始
 */
export const getActiveCourses = ({ keyword = '', trainerId = null, categoryId = null } = {}) =>
  api.get('/api/group-courses/public', { params: { keyword: keyword || undefined, trainerId: trainerId || undefined, categoryId: categoryId || undefined } })

/** 查詢即將開始、開放報名的團課場次，不需登入即可呼叫。行事曆用，一次回傳全部資料，不分頁 */
export const getUpcomingSessions = () => api.get('/api/member/group-classes')

/**
 * 報名指定的團課場次，需登入。memberId 由後端從登入身分解析，這裡不用傳
 * @param {number} sessionId 要報名的場次 id
 */
export const bookSession = (sessionId) => api.post(`/api/member/group-classes/${sessionId}/booking`)

/**
 * 分頁查詢自己的所有團課報名紀錄，可依課程名稱或教室搜尋，需登入
 * @param {{page?: number, size?: number, search?: string}} params page 從 0 開始
 */
export const getMyBookings = ({ page = 0, size = 10, search = '' } = {}) =>
  api.get('/api/member/group-classes/bookings', { params: { page, size, search } })

/**
 * 取消指定的團課報名，需登入。不像私教課取消需要二次確認額度消耗，
 * 這支不會回 CONFIRMATION_REQUIRED，一次 confirm() 確認即可
 * @param {number} bookingId 要取消的報名 id
 */
export const cancelBooking = (bookingId) => api.delete(`/api/member/group-classes/bookings/${bookingId}`)
