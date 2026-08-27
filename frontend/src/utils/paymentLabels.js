// 付款方式 / 交易狀態的顯示標籤。後端回傳綠界原始代碼（Credit_CreditCard 等），
// 前端統一在這裡對照成中文，比照 AdminReportView.vue 既有的 STATUS_LABELS 做法。

export const PAYMENT_METHOD_LABELS = {
  Credit_CreditCard: '信用卡',
  Credit: '信用卡',
  ATM: 'ATM 轉帳',
  WebATM: '網路 ATM',
  CVS: '超商代碼',
  BARCODE: '超商條碼',
  Simulated: '模擬付款（測試）',
  aio: '綠界整合金流',
}

export const PAYMENT_STATUS_LABELS = {
  PENDING: '處理中',
  SUCCESS: '成功',
  FAILED: '失敗',
}

export const REFUND_STATUS_LABELS = {
  PENDING: '待處理',
  COMPLETED: '已完成',
  REJECTED: '已駁回',
  CANCELLED: '已取消（重複申請）',
}

export const WEBHOOK_STATUS_LABELS = {
  RECEIVED: '已接收',
  PROCESSED: '已處理',
  ERROR: '處理失敗',
}

export const PAYMENT_PROVIDER_LABELS = {
  ECPAY: '綠界 ECPay',
  SIMULATE: '模擬付款',
}

export function paymentMethodLabel(code) {
  return PAYMENT_METHOD_LABELS[code] ?? code ?? '—'
}
