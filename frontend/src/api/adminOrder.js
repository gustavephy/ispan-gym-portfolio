import api from './axios.instance.js'

// 管理員訂單/退款後台，對應後端 AdminOrderController 的 /api/admin/orders/**

/**
 * 訂單搜尋（分頁），需登入且為 ADMIN。
 * @param {{status?: string, memberId?: number, createdFrom?: string, createdTo?: string,
 *           keyword?: string, page?: number, size?: number}} params
 *   status 省略代表全部狀態；createdFrom/createdTo 為 ISO 日期時間字串，比對下單時間；
 *   keyword 模糊比對訂單編號/綠界特店編號/收件人姓名/會員信箱；page 從 0 起算
 */
export const searchOrders = ({ status, memberId, createdFrom, createdTo, keyword, page = 0, size = 20 } = {}) =>
  api.get('/api/admin/orders', {
    params: { status: status || undefined, memberId: memberId || undefined, createdFrom, createdTo, keyword: keyword || undefined, page, size },
  })

/** 單筆訂單詳情：明細 + 狀態時間軸，需登入且為 ADMIN */
export const getAdminOrderDetail = (orderId) => api.get(`/api/admin/orders/${orderId}`)

/** 標記已出貨：PAID → SHIPPED，僅適用於含實體商品的訂單，需登入且為 ADMIN */
export const shipOrder = (orderId) => api.post(`/api/admin/orders/${orderId}/ship`)

/** 標記訂單完成：SHIPPED → COMPLETED，或無出貨步驟的訂單直接 PAID → COMPLETED，需登入且為 ADMIN */
export const completeOrder = (orderId) => api.post(`/api/admin/orders/${orderId}/complete`)

/** 查詢待人工處理的退款清單（會員取消已付款訂單後產生），需登入且為 ADMIN */
export const getPendingRefunds = () => api.get('/api/admin/orders/refunds/pending')

/** 完成一筆退款：還原庫存/優惠券、訂單轉 REFUNDED，需登入且為 ADMIN */
export const completeRefund = (refundId) => api.post(`/api/admin/orders/refunds/${refundId}/complete`)

/** 駁回一筆退款申請：不核准，訂單狀態不變，reason 選填，需登入且為 ADMIN */
export const rejectRefund = (refundId, reason) =>
  api.post(`/api/admin/orders/refunds/${refundId}/reject`, { reason: reason || null })
