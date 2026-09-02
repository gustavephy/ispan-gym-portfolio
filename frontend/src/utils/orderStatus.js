// 訂單狀態的顯示對照與共用格式化。OrderListView 與 PaymentResultView 共用，
// 避免兩處各自維護一份會漂移的對照表。

export const statusMeta = {
  UNPAID: { label: '待付款', className: 'warning' },
  PAID: { label: '已付款', className: 'info' },
  SHIPPED: { label: '已出貨', className: 'info' },
  COMPLETED: { label: '已完成', className: 'success' },
  CANCELLED: { label: '已取消', className: 'muted' },
  FAILED: { label: '付款失敗', className: 'danger' },
  REFUNDED: { label: '已退款', className: 'muted' },
}

// 狀態時間軸的事件說明；note 是後端寫死的短代碼（見 OrderService／EcpayService）
export const historyNoteMeta = {
  CHECKOUT: '建立訂單',
  ECPAY_NOTIFY: '綠界付款結果通知',
  SIMULATE_PAYMENT: '模擬付款（測試）',
  CANCEL: '取消訂單',
  REFUND_REQUEST: '申請退款',
  REFUND_COMPLETE: '退款完成',
  REFUND_REJECT: '退款申請遭駁回',
  SHIP: '出貨',
  COMPLETE: '訂單完成',
  QUERY_TRADE_INFO: '付款狀態',
}

// 可以申請退款（含出貨後退貨）的訂單狀態，跟後端 OrderService 的 REFUND_ELIGIBLE_STATUSES 對齊
export const REFUND_ELIGIBLE_STATUSES = ['PAID', 'SHIPPED', 'COMPLETED']

// 付款成功後訂單會落在的狀態（信用卡即時付款、或 ATM／超商繳費完成後同步到）
export const PAID_STATUSES = ['PAID', 'SHIPPED', 'COMPLETED']

export function statusLabel(status) {
  return statusMeta[status]?.label || status
}

export function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleString('zh-TW', { hour12: false })
}

export function formatMoney(amount) {
  return `NT$ ${Number(amount).toLocaleString()}`
}

// 私教課品項的補充說明：共 N 堂、負責教練
export function courseItemMeta(item) {
  const parts = []
  if (item.totalSessions != null) parts.push(`共 ${item.totalSessions} 堂`)
  if (item.trainerName) parts.push(`教練 ${item.trainerName}`)
  return parts.join('・')
}
