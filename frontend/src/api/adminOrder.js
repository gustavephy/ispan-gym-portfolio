import api from './axios.instance.js'

// 管理員訂單/退款後台，對應後端 AdminOrderController 的 /api/admin/orders/**

/** 查詢待人工處理的退款清單（會員取消已付款訂單後產生），需登入且為 ADMIN */
export const getPendingRefunds = () => api.get('/api/admin/orders/refunds/pending')

/** 完成一筆退款：還原庫存/優惠券、訂單轉 REFUNDED，需登入且為 ADMIN */
export const completeRefund = (refundId) => api.post(`/api/admin/orders/refunds/${refundId}/complete`)
