import api from './axios.instance.js'

// 後台金流查詢，對應後端 AdminPaymentController 的 /api/admin/payments/**（需 ADMIN）

/** 交易紀錄（分頁）：status / provider / from / to（ISO 日期時間）/ keyword / page / size */
export const searchPaymentTransactions = (params = {}) =>
  api.get('/api/admin/payments/transactions', { params: cleanParams(params) })

/** 退款紀錄（分頁，全部狀態）：status / from / to / keyword / page / size */
export const searchPaymentRefunds = (params = {}) =>
  api.get('/api/admin/payments/refunds', { params: cleanParams(params) })

/** 金流回呼（Webhook，分頁）：processingStatus / provider / checkMacValid / from / to / keyword / page / size */
export const searchPaymentWebhooks = (params = {}) =>
  api.get('/api/admin/payments/webhooks', { params: cleanParams(params) })

function cleanParams(params) {
  const out = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== '' && v !== null && v !== undefined) out[k] = v
  }
  return out
}
