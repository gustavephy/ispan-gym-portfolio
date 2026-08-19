import api from './axios.instance.js'

// 管理員排定團課場次，對應後端 AdminGroupClassController 的
// POST /api/employee/group-classes、DELETE /api/employee/group-classes/{sessionId}。
// 跟團課主檔管理（adminGroupCourse.js）分開檔案，職責不同：
// 那邊管課程本身（教練、名稱、預設容量……），這裡管特定日期/教室的場次。

/**
 * 分頁查詢團課場次列表，可依課程名稱、教練姓名或教室搜尋，需登入且為 ADMIN。
 * 後台專用端點，跟會員瀏覽用的 GroupClassController#getUpcomingSessions（行事曆用，
 * 一次回傳全部資料）分開，不要混用。
 * @param {{page?: number, size?: number, search?: string}} params page 從 0 開始
 */
export const getSessions = ({ page = 0, size = 10, search = '' } = {}) =>
  api.get('/api/employee/group-classes', { params: { page, size, search } })

/**
 * 建立團課場次，需登入且為 ADMIN
 * @param {{groupCourseId: number, roomName: string, startAt: string, endAt: string, capacity?: number}} payload
 */
export const createSession = (payload) => api.post('/api/employee/group-classes', payload)

/**
 * 修改團課場次，需登入且為 ADMIN。version 為樂觀鎖版本，必須帶目前列表拿到的值，
 * 版本不符會回 409（見 GroupClassService.updateSession）。capacity 留空維持原本容量
 * @param {number} id 要修改的場次 id
 * @param {{roomName: string, startAt: string, endAt: string, capacity?: number, version: number}} payload
 */
export const updateSession = (id, payload) => api.put(`/api/employee/group-classes/${id}`, payload)

/**
 * 取消團課場次，需登入且為 ADMIN
 * DELETE 慣例上不帶 body，acknowledgeExistingBooking 用 query string 傳
 * @param {number} sessionId 要取消的場次 id
 * @param {boolean} acknowledgeExistingBooking 該場次已有會員報名時，是否二次確認要一併取消
 */
export const cancelSession = (sessionId, acknowledgeExistingBooking) =>
  api.delete(`/api/employee/group-classes/${sessionId}`, { params: { acknowledgeExistingBooking } })
