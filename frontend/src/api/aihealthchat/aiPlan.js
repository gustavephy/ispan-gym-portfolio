import api from '../axios.instance.js'

/**
 * AI 個人化健身課表（docs/API-REFERENCE.md 13.2）。
 * POST 走 Spring MVC async，後端逾時上限 130 秒；axios.instance.js 已把全域 timeout
 * 設為 140000ms 就是為了這支 API，這裡不需要另外覆寫。
 */
export const generatePlan = (payload) => api.post('/api/ai-plans', payload)
export const getPlanHistory = () => api.get('/api/ai-plans')

/** 下載課表 PDF；responseType 必須是 blob，否則二進位內容會被當字串解析而毀損 */
export const downloadPlanPdf = (id) =>
  api.get(`/api/ai-plans/${id}/pdf`, { responseType: 'blob' })
