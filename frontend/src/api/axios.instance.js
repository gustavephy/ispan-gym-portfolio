/**
 * Axios 封裝 — API 請求統一出口
 *
 * 為什麼需要封裝？
 *   1. 統一設定 baseURL，不需要每個 API call 都寫完整 URL
 *   2. Request 攔截器：自動在 Header 加上 JWT Token
 *   3. Response 攔截器：把後端統一的 ApiErrorResponse 正規化，並分流處理
 *
 * 後端錯誤格式（docs/API-REFERENCE.md 1.6）：
 *   { code: String, message: String, timestamp: LocalDateTime }
 * filter 層的 401/403 也是同一格式，所以前端只需要一套解析邏輯。
 *
 * 使用方式：
 *   import api from '@/api/axios.instance.js'
 *   const products = await api.get('/api/products')
 *
 * 錯誤處理：reject 出去的一律是 normalizeApiError() 的結果，呼叫端可直接讀 .code / .message
 *   try { await api.post('/api/orders') }
 *   catch (e) { if (e.code === 'INSUFFICIENT_STOCK') ... }
 */
import axios from 'axios'
import { useAuthStore } from '@/stores/auth.js'
import { normalizeApiError, isInlineError, CODE_SUBSCRIPTION_REQUIRED } from './api-error.js'
import { notifyApiError, notifySubscriptionRequired, notifySessionExpired } from './error-bus.js'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/', // 優先使用環境變數，否則由 Vite 代理到 localhost:8080
  // AI 課表生成走 Spring MVC async，後端逾時上限 130 秒，
  // 前端必須比後端寬，否則會在後端還在算時就自己斷線（見 API-REFERENCE 13.2）
  timeout: 140000,
  headers: {
    'Content-Type': 'application/json',
    'ngrok-skip-browser-warning': 'true', // 繞過 ngrok 免費版的瀏覽器警告頁面以防阻擋 CORS
    'Bypass-Tunnel-Reminder': 'true', // 繞過 localtunnel (serveousercontent) 的警告頁面
  },
})

/** 這些端點就算回 401 也不該觸發「登出並導向登入頁」——它們本來就是未登入狀態在打 */
const ANONYMOUS_ENDPOINTS = [
  '/api/auth/login',
  '/api/members/register',
  '/api/employees/register',
  '/api/employees/reapply',
  '/api/accounts/verify-email',
  '/api/accounts/resend-verification-email',
]

function isAnonymousEndpoint(url = '') {
  return ANONYMOUS_ENDPOINTS.some((path) => url.includes(path))
}

// ==============================
// Request 攔截器（發出請求前執行）
// 自動從 Pinia store 取出 JWT Token 加入 Header
// ==============================
api.interceptors.request.use(
  (config) => {
    try {
      const auth = useAuthStore()
      if (auth.token) {
        config.headers['Authorization'] = `Bearer ${auth.token}`
      }
    } catch (e) {
      // Pinia 尚未初始化（例如在 store 建立前就發請求），略過附加 token
    }
    return config
  },
  (error) => Promise.reject(normalizeApiError(error)),
)

// ==============================
// Response 攔截器（收到回應後執行）
// 統一把後端 ApiErrorResponse 正規化後再分流
// ==============================
api.interceptors.response.use(
  // 成功回應：直接回傳
  (response) => response,

  // 錯誤回應：正規化 → 依類型分流 → 一律 reject 正規化後的物件
  (error) => {
    const apiError = normalizeApiError(error)
    const url = error.config?.url ?? ''
    const skipGlobal = error.config?.skipGlobalError === true

    // 401：token 過期或未登入。登入／註冊等匿名端點的 401 屬於「帳密錯誤」之類的
    // 正常結果，交給呼叫端自己顯示，不可清 token 也不該導向登入頁。
    // skipGlobal 為 true 表示呼叫端自行處理（例如首頁選填 API），同樣跳過全域登出流程。
    if (apiError.status === 401 && !isAnonymousEndpoint(url)) {
      if (!skipGlobal) {
        try {
          useAuthStore().logout()
        } catch (e) {
          // store 不可用時仍要繼續通知，不讓例外中斷錯誤流程
        }
        notifySessionExpired(apiError)
      }
      return Promise.reject(apiError)
    }

    // 403 SUBSCRIPTION_REQUIRED：不是真的「沒權限」，而是要引導去訂閱，
    // 單獨拉出來讓 UI 可以導向訂閱頁而非只跳錯誤框（見 API-REFERENCE 5.1）
    if (apiError.code === CODE_SUBSCRIPTION_REQUIRED) {
      if (!skipGlobal) {
        notifySubscriptionRequired(apiError)
      }
      return Promise.reject(apiError)
    }

    // 409 CONFIRMATION_REQUIRED：需要使用者確認後帶參數重送，屬於預期中的流程分支，
    // 一律交給呼叫端跳自己的確認視窗，全域不介入（見 API-REFERENCE 8.6）
    if (apiError.needsConfirmation) {
      return Promise.reject(apiError)
    }

    // 其餘錯誤：業務類（表單驗證、庫存不足等）由呼叫端就地顯示，
    // 系統類（500、503、網路失敗）才跳全域提示
    if (!skipGlobal && !isInlineError(apiError)) {
      notifyApiError(apiError)
    }

    return Promise.reject(apiError)
  },
)

export default api
