import api from '../axios.instance.js'

// 取得所有優惠券
export const adminGetCoupons = () => api.get('/api/admin/coupons')

// 建立優惠券
export const adminCreateCoupon = (data) => api.post('/api/admin/coupons', data)

// 編輯優惠券
export const adminUpdateCoupon = (id, data) => api.put(`/api/admin/coupons/${id}`, data)

// 刪除優惠券
export const adminDeleteCoupon = (id) => api.delete(`/api/admin/coupons/${id}`)

// 一鍵發放所有會員優惠券
export const adminDistributeCouponAll = (id) => api.post(`/api/admin/coupons/${id}/distribute-all`)
