import api from './axios.instance.js'

// 會員瀏覽／報名團體課程場次，對應後端 GroupClassController 的
// GET /api/member/group-classes、POST /api/member/group-classes/{sessionId}/booking。

/** 查詢即將開始、開放報名的團課場次，不需登入即可呼叫 */
export const getUpcomingSessions = () => api.get('/api/member/group-classes')

/**
 * 報名指定的團課場次，需登入。memberId 由後端從登入身分解析，這裡不用傳
 * @param {number} sessionId 要報名的場次 id
 */
export const bookSession = (sessionId) => api.post(`/api/member/group-classes/${sessionId}/booking`)
