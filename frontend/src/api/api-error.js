/**
 * 後端錯誤格式的統一解析工具。
 *
 * 後端所有錯誤（含 filter 層的 401/403）都回同一個 ApiErrorResponse：
 *   { code: String, message: String, timestamp: LocalDateTime }
 * 詳見 docs/API-REFERENCE.md 第 1.6 節。
 *
 * 這裡把「HTTP 狀態碼 + 業務 code」正規化成前端好判斷的物件，
 * 讓呼叫端不必各自去挖 error.response.data.code。
 */

/** 需要使用者二次確認、補參數後重送的錯誤碼（非真正的失敗） */
export const CODE_CONFIRMATION_REQUIRED = 'CONFIRMATION_REQUIRED'

/** 沒有訂閱就買私教課；前端應導向訂閱頁而不是單純報錯 */
export const CODE_SUBSCRIPTION_REQUIRED = 'SUBSCRIPTION_REQUIRED'

/**
 * 帳號安全的敏感操作（Google 綁定／解除）要求重新輸入目前密碼，密碼錯誤時後端回
 * 401 REAUTHENTICATION_REQUIRED——這是「這次操作要再驗證一次」，不是「登入 token 失效」，
 * 攔截器不可把它當 session 過期而清掉登入狀態，交由呼叫端就地顯示在 Modal 裡。
 */
export const CODE_REAUTHENTICATION_REQUIRED = 'REAUTHENTICATION_REQUIRED'

/**
 * 這些錯誤屬於「使用者操作結果」，畫面上該由呼叫端就地顯示（表單旁、按鈕下方），
 * 不該跳全域錯誤視窗洗版。其餘未列出的才視為系統層級錯誤。
 */
const INLINE_ERROR_CODES = new Set([
  'INVALID_REQUEST',
  'BUSINESS_RULE_VIOLATION',
  'CONFIRMATION_REQUIRED',
  'SUBSCRIPTION_REQUIRED',
  'REAUTHENTICATION_REQUIRED',
  'INSUFFICIENT_STOCK',
  'INVALID_QUANTITY',
  'PRODUCT_UNAVAILABLE',
  'DIRECT_CHECKOUT_NOT_ALLOWED',
  'CART_ITEM_TYPE_NOT_ALLOWED',
  'DUPLICATE_INBODY_RECORD',
  'MISSING_INBODY_MEASUREMENT',
  'INVALID_GOAL',
  'COURSE_FULL',
  'HTTP_404',
])

/** 後端沒回 message 時的預設字串，依狀態碼給比「發生錯誤」更具體的說法 */
const FALLBACK_MESSAGE = {
  400: '請確認輸入資料是否正確',
  401: '登入狀態已失效，請重新登入',
  403: '沒有權限執行此操作',
  404: '找不到資料',
  405: '不支援的請求方法',
  409: '資料狀態已變更，請重新整理後再試',
  500: '系統發生錯誤，請稍後再試',
  503: '服務暫時無法使用，請稍後再試',
}

/**
 * 把 axios 的 error 正規化成統一結構。
 *
 * @returns {{
 *   code: string,          業務錯誤碼，如 INSUFFICIENT_STOCK；無回應時為 NETWORK_ERROR
 *   message: string,       可直接顯示給使用者的訊息
 *   status: number|null,   HTTP 狀態碼；連線失敗時為 null
 *   timestamp: string|null,
 *   isNetworkError: boolean,
 *   needsConfirmation: boolean,  是否為 409 CONFIRMATION_REQUIRED
 *   raw: unknown           原始 error，需要細節時自行取用
 * }}
 */
export function normalizeApiError(error) {
  const response = error?.response
  const status = response?.status ?? null
  const body = response?.data

  // 連線失敗／逾時／CORS 被擋：沒有 response 可解析
  if (!response) {
    const isTimeout = error?.code === 'ECONNABORTED'
    return {
      code: isTimeout ? 'TIMEOUT' : 'NETWORK_ERROR',
      message: isTimeout ? '請求逾時，請稍後再試' : '網路連線失敗或伺服器無回應',
      status: null,
      timestamp: null,
      isNetworkError: true,
      needsConfirmation: false,
      raw: error,
    }
  }

  // PDF 等 blob 回應出錯時 data 會是 Blob，拿不到 code，只能靠狀態碼
  const isParsableBody = body && typeof body === 'object' && !(body instanceof Blob)
  const code = (isParsableBody && body.code) || `HTTP_${status}`
  const message =
    (isParsableBody && body.message) || FALLBACK_MESSAGE[status] || '系統發生錯誤，請稍後再試'

  return {
    code,
    message,
    status,
    timestamp: (isParsableBody && body.timestamp) || null,
    isNetworkError: false,
    needsConfirmation: status === 409 && code === CODE_CONFIRMATION_REQUIRED,
    raw: error,
  }
}

/** 是否該由呼叫端就地顯示（而非跳全域錯誤視窗） */
export function isInlineError(apiError) {
  return INLINE_ERROR_CODES.has(apiError.code)
}
