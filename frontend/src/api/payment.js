import api from './axios.instance.js'

// 組出綠界 ECPay AioCheckOut 表單欄位（含 CheckMacValue）；訂單建立後呼叫
export const getEcpayCheckoutForm = (orderId) => api.post(`/api/payments/ecpay/checkout/${orderId}`)
