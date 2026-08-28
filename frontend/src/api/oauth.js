import api from './axios.instance.js'

/**
 * Google OAuth 登入流程的兩支匿名端點（呼叫時前端還沒有 JWT）。
 * 沿用 api/auth.js 同一套慣例：
 *   - skipAuthRedirect：這裡的錯誤（交換碼無效、ticket 過期等）是流程中的正常分支，
 *     不是「token 失效」，攔截器不該清登入狀態或導去登入頁。
 *   - skipGlobalError：錯誤由 OAuth callback／補資料頁就地顯示，不跳全域提示條。
 *
 * 後端契約見 docs/google-oauth-design-notes.md 與 controller/account/OAuth*Controller。
 * 註：後端 docs/API-REFERENCE.md 尚未收錄 OAuth 章節。
 */
const ANONYMOUS_CONFIG = { skipAuthRedirect: true, skipGlobalError: true }

/**
 * 「登入前所在頁面」路徑的單一驗證來源，三處共用（googleLoginUrl 組發起網址、
 * OAuthCallbackView／OAuthRegisterView 決定登入後導去哪）。只接受站內相對路徑，
 * 擋掉絕對 URL、protocol-relative（`//`、`/\`）、控制字元、過長值——open redirect 縱深防禦。
 * 後端 ReturnPathValidator 仍是最終驗證來源，這裡只是讓前端規則不分岔。
 *
 * @param {unknown} path
 * @returns {string|null} 合法則回傳去頭尾空白後的字串，否則 null
 */
export function sanitizeReturnPath(path) {
  if (typeof path !== 'string') return null
  const trimmed = path.trim()
  if (trimmed.length === 0 || trimmed.length > 500) return null
  if (!trimmed.startsWith('/') || trimmed.startsWith('//')) return null
  if (trimmed.charAt(1) === '\\') return null
  for (let i = 0; i < trimmed.length; i++) {
    const c = trimmed.charCodeAt(i)
    if (c <= 0x1f || c === 0x7f) return null // 控制字元
  }
  return trimmed
}

/**
 * Google 登入入口網址。這是整頁導向用的（window.location.href = ...），不是 XHR，
 * 所以不受 CORS 限制、也不會經過 axios。
 *
 * base 與 axios 的 baseURL 對齊：沒設 VITE_API_URL 時打相對路徑（開發時由 vite proxy
 * 的 /oauth2 轉到 :8080）；有設時打絕對網址（需為後端 origin，結尾不含 /api，見 .env.example）。
 *
 * @param {string} [returnPath] 登入前所在的站內相對路徑，登入成功後導回。
 */
export function googleLoginUrl(returnPath) {
  const base = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')
  const url = `${base}/oauth2/authorization/google`
  const clean = sanitizeReturnPath(returnPath)
  return clean ? `${url}?returnPath=${encodeURIComponent(clean)}` : url
}

/**
 * POST /api/auth/oauth/exchange
 * body: { code }（一次性交換碼，來自後端導回網址的 ?code=）
 * 回應 OAuthExchangeResponse: { accessToken, accountId, email, roles, returnPath }
 *   - 形狀與 auth store 的 setSession() 相容，可直接傳入。
 *   - returnPath：登入前保存、後端已驗證過的站內相對路徑，沒有則為 null。
 * 失敗一律 400 OAUTH_EXCHANGE_CODE_INVALID（不存在／已使用／已過期／帳號已不可登入皆同一訊息）。
 * 交換碼是單次使用，失敗不可自動重試——只能請使用者重新用 Google 登入。
 */
export const exchangeCode = (code) => api.post('/api/auth/oauth/exchange', { code }, ANONYMOUS_CONFIG)

/**
 * POST /api/auth/oauth/google/registration/complete
 * body: OAuthRegistrationCompleteRequest
 *   { registrationToken, firstName, lastName, phone, dob?, gender? }
 *   - registrationToken 來自後端導回網址的 ?token=（注意 body 欄位名是 registrationToken）。
 *   - email／Google 身分一律由後端 ticket 決定，不在這裡傳。
 *   - phone 需符合 ^09\d{8}$；dob 為 yyyy-MM-dd 且須為過去；gender ∈ { M, F, OTHER }。
 * 回應: { code } —— 再拿去 exchangeCode() 換 JWT。
 * 失敗：400 OAUTH_REGISTRATION_TICKET_INVALID（ticket 無效／過期／已用）、
 *       400 INVALID_REQUEST（欄位驗證失敗）、
 *       409 OAUTH_ACCOUNT_LINK_REQUIRED（補資料期間 email 被別的帳號搶先註冊）。
 */
export const completeGoogleRegistration = (payload) =>
  api.post('/api/auth/oauth/google/registration/complete', payload, ANONYMOUS_CONFIG)
