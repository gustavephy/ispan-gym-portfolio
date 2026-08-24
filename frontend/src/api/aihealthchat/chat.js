import api from '../axios.instance.js'

/**
 * 聊天室 REST 面（歷史／已讀／未讀數）。即時收發走 WebSocket，見 chatSocket.js。
 * 對應 docs/API-REFERENCE.md 13.3。
 */

/** counterpartType: 'AI' | 'EMPLOYEE'；EMPLOYEE 時 employeeId 必填 */
export const getChatHistory = ({ counterpartType, employeeId, page = 0, size = 20 }) =>
  api.get('/api/chat/history', { params: { counterpartType, employeeId, page, size } })

export const markChatRead = ({ counterpartType, employeeId }) =>
  api.patch('/api/chat/read', null, { params: { counterpartType, employeeId } })

export const getUnreadCount = () => api.get('/api/chat/unread-count')

/** 202 Accepted：立即回傳，實際推薦訊息稍後由 WebSocket 推送到 AI 房間 */
export const recommendCourse = () => api.post('/api/chat/recommend-course')

/** 「學員訊息」列表：曾與我對話過的學員，依最後訊息時間排序，附預覽與未讀數 */
export const getConversations = () => api.get('/api/employee/chat/conversations')

export const getEmployeeChatHistory = ({ memberId, page = 0, size = 20 }) =>
  api.get('/api/employee/chat/history', { params: { memberId, page, size } })

export const markEmployeeChatRead = (memberId) =>
  api.patch('/api/employee/chat/read', null, { params: { memberId } })

export const getEmployeeUnreadCount = () => api.get('/api/employee/chat/unread-count')
