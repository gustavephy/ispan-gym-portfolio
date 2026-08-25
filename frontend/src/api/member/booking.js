import api from '../axios.instance.js'

// 取得我的預約紀錄
export const getMyReservations = () => api.get('/api/member/bookings')

// 取得我的課程額度
export const getMyQuotas = () => api.get('/api/member/quotas')

// 產生報到金鑰
export const generateCheckInKey = (reservationId) => api.post(`/api/reservations/${reservationId}/check-in-key`)

// 申請取消預約
export const requestCancel = (reservationId) => api.post(`/api/reservations/${reservationId}/cancel`)

