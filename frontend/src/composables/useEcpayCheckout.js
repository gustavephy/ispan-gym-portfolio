// 綠界結帳轉導的共用邏輯：取得付款表單 → 記下待確認訂單 → 顯示全屏過場 → 送出表單導向綠界。
//
// 為什麼是 module-level 單例 state：purchaseCourse 在 Pinia store（courses.js）裡呼叫，
// 不在 component setup 內，不能用一般 composable 的 per-instance ref。改成模組層單例，
// EcpayRedirectOverlay.vue 在 DefaultLayout 掛一次讀同一份 state。
//
// 為什麼要 12 秒 timeout：submitEcpayForm() 送出後瀏覽器正常會立刻離開本頁，
// 過場動畫隨頁面卸載一起消失。若表單 action、網路或瀏覽器攔截讓導頁沒發生，
// 使用者會卡在過場畫面——timeout 收掉過場、給一個「前往我的訂單」的出口。
import { reactive } from 'vue'
import { getEcpayCheckoutForm } from '@/api/payment.js'
import { submitEcpayForm } from '@/utils/ecpay.js'

const REDIRECT_TIMEOUT_MS = 12000

const state = reactive({ redirecting: false, timedOut: false })
let timer = null

function armTimeout() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    state.redirecting = false
    state.timedOut = true
  }, REDIRECT_TIMEOUT_MS)
}

function dismissTimeout() {
  state.timedOut = false
}

/**
 * 對指定訂單取得綠界付款表單並送出導向綠界。
 * 成功時瀏覽器隨即離開本頁；失敗（取表單就出錯）會清掉過場並把錯誤往外拋，由呼叫端顯示。
 *
 * pendingPaymentOrderId 對所有付款方式都寫入——ATM／超商代碼雖非即時付款，
 * 也要讓返回後的 /payment/result 頁接住並顯示繳費資訊。
 */
async function redirectToEcpay(orderId, { method = 'Credit' } = {}) {
  state.redirecting = true
  state.timedOut = false
  try {
    const { data } = await getEcpayCheckoutForm(orderId, method)
    localStorage.setItem('pendingPaymentOrderId', String(orderId))
    armTimeout()
    submitEcpayForm(data)
  } catch (err) {
    clearTimeout(timer)
    state.redirecting = false
    localStorage.removeItem('pendingPaymentOrderId')
    throw err
  }
}

/**
 * 送出後端已組好的綠界表單（訂閱一步到位結帳 checkoutDirectSubscriptionWithEcpay 用），
 * 不必再打一次取表單的 API。
 */
function submitPrebuiltEcpayForm(ecpayForm, orderId) {
  state.redirecting = true
  state.timedOut = false
  if (orderId) localStorage.setItem('pendingPaymentOrderId', String(orderId))
  armTimeout()
  submitEcpayForm(ecpayForm)
}

export function useEcpayCheckout() {
  return { state, redirectToEcpay, submitPrebuiltEcpayForm, dismissTimeout }
}

// 供 Pinia store 等非 setup 情境直接匯入
export { state as ecpayRedirectState, redirectToEcpay, submitPrebuiltEcpayForm, dismissTimeout }
