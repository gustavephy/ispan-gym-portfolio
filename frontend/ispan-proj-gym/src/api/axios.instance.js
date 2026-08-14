/**
 * Axios 封裝 — API 請求統一出口
 *
 * 為什麼需要封裝？
 *   1. 統一設定 baseURL，不需要每個 API call 都寫完整 URL
 *   2. Request 攔截器：自動在 Header 加上 JWT Token
 *   3. Response 攔截器：統一處理 401（Token 過期）→ 自動跳登入頁
 *
 * 使用方式：
 *   import api from '@/api/axios.instance.js'
 *   const products = await api.get('/api/products')
 */
import axios from 'axios'
import { useAuthStore } from '@/stores/auth.js'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/',        // 優先使用環境變數，否則由 Vite 代理到 localhost:8080
  timeout: 30000,      // 30 秒超時（OpenAI API 可能較慢）
  headers: {
    'Content-Type': 'application/json',
    'ngrok-skip-browser-warning': 'true',               // 繞過 ngrok 免費版的瀏覽器警告頁面以防阻擋 CORS
    'Bypass-Tunnel-Reminder': 'true'                    // 繞過 localtunnel (serveousercontent) 的警告頁面
  }
})

// ==============================
// Request 攔截器（發出請求前執行）
// 自動從 Pinia store 取出 JWT Token 加入 Header
// ==============================
api.interceptors.request.use(config => {
  try {
    const auth = useAuthStore()
    if (auth.token) {
      config.headers['Authorization'] = `Bearer ${auth.token}`
    }
  } catch (e) {
    // Pinia not initialized yet
  }
  return config
}, error => Promise.reject(error))

// ==============================
// Response 攔截器（收到回應後執行）
// 統一處理各種 HTTP 錯誤
// ==============================
api.interceptors.response.use(
  // 成功回應：直接回傳
  response => response,

  // 錯誤回應：分情況處理
  error => {
    const status = error.response?.status
    const isLoginRequest = error.config?.url?.includes('/auth/login')

    if (status === 401) {
      if (!isLoginRequest) {
        // Token 過期或未登入 → 清除 Token，導向登入頁
        try {
          const auth = useAuthStore()
          auth.logout()
        } catch (e) {}
        window.location.href = '/login'
      }
    } else if (status === 403) {
      console.warn('403 Forbidden:', error.response?.data?.message)
      window.dispatchEvent(new CustomEvent('show-system-error', {
        detail: {
          title: '權限不足',
          message: error.response?.data?.message || '您沒有權限執行此操作。'
        }
      }))
    } else {
      const errorMsg = error.response?.data?.message || error.response?.data?.error || error.message || '網路連線失敗或伺服器無回應'
      
      // 登入請求或特別指定跳過全域錯誤的請求由呼叫端自己處理，不彈出全域錯誤
      if (!isLoginRequest && !error.config?.skipGlobalError) {
        window.dispatchEvent(new CustomEvent('show-system-error', {
          detail: {
            title: '系統執行錯誤',
            message: errorMsg
          }
        }))
      }
    }

    return Promise.reject(error)
  }
)

export default api