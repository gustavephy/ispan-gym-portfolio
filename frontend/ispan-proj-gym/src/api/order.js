import api from './axios.instance.js'

// 購物車結帳（一般商品）：payload 可省略或帶 { couponCode, receiverName, receiverPhone, receiverAddress }
export const checkoutFromCart = (payload) => api.post('/api/orders', payload)

// 課程略過購物車直接結帳：{ privateCourseId, quantity, couponCode }
export const checkoutDirect = (payload) => api.post('/api/orders/direct', payload)

// 會員訂閱直接結帳（不含 ECPay 表單）：{ subscriptionPlanId, couponCode }
export const checkoutDirectSubscription = (payload) => api.post('/api/orders/direct/subscription', payload)

// 會員訂閱一步到位結帳：建立訂單並一併回傳 ECPay 表單欄位
export const checkoutDirectSubscriptionWithEcpay = (payload) =>
  api.post('/api/orders/direct/subscription/checkout', payload)

// 查詢自己名下的訂單列表
export const getMyOrders = () => api.get('/api/orders')

// 查詢單一訂單明細
export const getOrderById = (orderId) => api.get(`/api/orders/${orderId}`)

// 取消未付款訂單，或對已付款訂單建立一筆待處理退款：payload 可省略或帶 { reason }
export const cancelOrder = (orderId, payload) => api.post(`/api/orders/${orderId}/cancel`, payload)
