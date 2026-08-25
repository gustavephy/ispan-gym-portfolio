import api from '../axios.instance.js'

// 取得可使用的折價券清單
export const getAvailableCounpons = () => api.get('/api/coupons/available')

// 領取折價券
export const getClaimCoupon = (couponId) => api.post(`/api/coupons/claim/${couponId}`)

// 取得我的優惠券
export const getMyCoupons = () => api.get('/api/coupons/my')

// 取得我未使用的優惠券
export const getMyUnusedCoupons = () => api.get('/api/coupons/my-unused')

