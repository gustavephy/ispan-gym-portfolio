import api from '../axios.instance.js'

/** 公開教練介紹列表（docs/API-REFERENCE.md 8.7），聊天室「教練」分頁用來選擇對話對象 */
export const getTrainers = () => api.get('/api/trainers')
