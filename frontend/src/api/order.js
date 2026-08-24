import api from './axios.instance.js'

// idempotencyKey 選填：帶上時同一把金鑰（同一動作）重送只會真正執行一次，
// 由呼叫端在發起動作當下產生一次 UUID 並全程沿用，防止手滑連點/重送造成重複建單，見 IdempotencyService
const idempotentConfig = (idempotencyKey) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

// 購物車結帳（一般商品）：payload 可省略或帶 { couponCode, receiverName, receiverPhone, receiverAddress }
export const checkoutFromCart = (payload, idempotencyKey) =>
  api.post('/api/orders', payload, idempotentConfig(idempotencyKey))

// 課程略過購物車直接結帳：{ privateCourseId, quantity, couponCode }
export const checkoutDirect = (payload, idempotencyKey) =>
  api.post('/api/orders/direct', payload, idempotentConfig(idempotencyKey))

// 會員訂閱直接結帳（不含 ECPay 表單）：{ subscriptionPlanId, couponCode }
export const checkoutDirectSubscription = (payload, idempotencyKey) =>
  api.post('/api/orders/direct/subscription', payload, idempotentConfig(idempotencyKey))

// 會員訂閱一步到位結帳：建立訂單並一併回傳 ECPay 表單欄位
export const checkoutDirectSubscriptionWithEcpay = (payload, idempotencyKey) =>
  api.post('/api/orders/direct/subscription/checkout', payload, idempotentConfig(idempotencyKey))

// 查詢自己名下的訂單列表
export const getMyOrders = () => api.get('/api/orders')

// 查詢單一訂單明細
export const getOrderById = (orderId) => api.get(`/api/orders/${orderId}`)

// 查詢訂單狀態時間軸（下單/付款/出貨/取消/退款等事件，依時間先後排序）
export const getOrderHistory = (orderId) => api.get(`/api/orders/${orderId}/history`)

// 取消未付款訂單，或對已付款訂單建立一筆待處理退款：payload 可省略或帶 { reason }
export const cancelOrder = (orderId, payload, idempotencyKey) =>
  api.post(`/api/orders/${orderId}/cancel`, payload, idempotentConfig(idempotencyKey))
