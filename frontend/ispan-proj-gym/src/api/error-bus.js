/**
 * 全域錯誤事件匯流排。
 *
 * 攔截器不直接操作 UI（也不該直接 import router 或元件），而是發事件出來，
 * 由 App.vue 掛一個監聽者決定怎麼呈現。這樣攔截器不依賴任何 UI 實作，
 * 之後要換成 Toast、Dialog 或第三方元件庫都不必改 axios 那一層。
 *
 * 事件型別：
 *   api-error            一般系統錯誤，顯示錯誤提示
 *   session-expired      401，需重新登入
 *   subscription-required 403 SUBSCRIPTION_REQUIRED，應引導至訂閱頁
 */

export const EVENT_API_ERROR = 'api-error'
export const EVENT_SESSION_EXPIRED = 'session-expired'
export const EVENT_SUBSCRIPTION_REQUIRED = 'subscription-required'

function emit(type, apiError) {
  window.dispatchEvent(new CustomEvent(type, { detail: apiError }))
}

export function notifyApiError(apiError) {
  emit(EVENT_API_ERROR, apiError)
}

export function notifySessionExpired(apiError) {
  emit(EVENT_SESSION_EXPIRED, apiError)
}

export function notifySubscriptionRequired(apiError) {
  emit(EVENT_SUBSCRIPTION_REQUIRED, apiError)
}

/**
 * 註冊三種錯誤的處理函式，回傳解除註冊的函式。
 * 通常在 App.vue 的 onMounted 呼叫、onUnmounted 時解除。
 */
export function onApiErrors({ onError, onSessionExpired, onSubscriptionRequired } = {}) {
  const handlers = [
    [EVENT_API_ERROR, onError],
    [EVENT_SESSION_EXPIRED, onSessionExpired],
    [EVENT_SUBSCRIPTION_REQUIRED, onSubscriptionRequired],
  ].filter(([, handler]) => typeof handler === 'function')

  const wrapped = handlers.map(([type, handler]) => {
    const listener = (event) => handler(event.detail)
    window.addEventListener(type, listener)
    return () => window.removeEventListener(type, listener)
  })

  return () => wrapped.forEach((off) => off())
}
