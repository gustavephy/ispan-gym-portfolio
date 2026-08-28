import api from './axios.instance.js'

// 組出綠界 ECPay AioCheckOut 表單欄位（含 CheckMacValue）；訂單建立後呼叫
// method：Credit（預設，信用卡）／ATM（虛擬帳號轉帳）／CVS（超商代碼繳費）
export const getEcpayCheckoutForm = (orderId, method = 'Credit') =>
  api.post(`/api/payments/ecpay/checkout/${orderId}`, null, { params: { method } })
