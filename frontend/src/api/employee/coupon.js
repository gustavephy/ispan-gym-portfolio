import api from './axios-instance.js'

// 取得所有優惠券
export const employeeGetCoupons = () => api.get('/api/employee/coupons')

// 建立優惠券
export const employeeCreateCoupon = (data) => api.post('/api/employee/coupons', data)

// 編輯優惠券
export const employeeUpdateCoupon = (id, data) => api.put(`/api/employee/coupons/${id}`, data)

// 刪除優惠券
export const employeeDeleteCoupon = (id) => api.delete(`/api/employee/coupons/${id}`)

// 一鍵發放所有會員優惠券
export const employeeDistributeCouponAll = (id) => api.post(`/api/employee/coupons/${id}/distribute-all`)