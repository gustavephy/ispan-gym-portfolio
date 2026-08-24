import api from '../axios.instance.js'

/** 公開教練介紹列表（docs/API-REFERENCE.md 8.7） */
export const getTrainers = () => api.get('/api/trainers')

/** 聊天室「教練」分頁選擇對話對象用，登入會員限定，含 employeeId（公開列表不可外洩此欄位） */
export const getTrainerChatDirectory = () => api.get('/api/member/trainers/chat-directory')
