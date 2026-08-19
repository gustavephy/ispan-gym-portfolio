import api from '../axios.instance.js'

/**
 * InBody 量測紀錄 CRUD（docs/API-REFERENCE.md 13.1）。
 * 五項量測值（height/weight/bodyFat/muscleMass/bmi）皆可為 null，但至少要填一項，
 * 否則後端回 400 MISSING_INBODY_MEASUREMENT；同一天只能有一筆，重複回 409 DUPLICATE_INBODY_RECORD。
 */
export const getInbodyHistory = () => api.get('/api/inbody')
export const getInbodyRecord = (id) => api.get(`/api/inbody/${id}`)
export const createInbodyRecord = (payload) => api.post('/api/inbody', payload)
export const updateInbodyRecord = (id, payload) => api.put(`/api/inbody/${id}`, payload)
export const deleteInbodyRecord = (id) => api.delete(`/api/inbody/${id}`)
