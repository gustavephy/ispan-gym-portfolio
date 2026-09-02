import api from './axios.instance.js'

/**
 * Google OAuth 登入流程的兩支匿名端點（呼叫時前端還沒有 JWT）。
 * 沿用 api/auth.js 同一套慣例：
 *   - skipAuthRedirect：這裡的錯誤（交換碼無效、ticket 過期等）是流程中的正常分支，
 *     不是「token 失效」，攔截器不該清登入狀態或導去登入頁。
 *   - skipGlobalError：錯誤由 OAuth callback／補資料頁就地顯示，不跳全域提示條。
 *
 * 後端契約見 docs/google-oauth-design-notes.md 與 controller/account/OAuth*Controller，
 * 端點細節見 docs/API-REFERENCE.md §2.12（OAuthConnectionController）。
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
 * 後端 origin 的單一來源，給「整頁導向 /oauth2/... 」用（不是 XHR，不經過 axios、
 * 不受 CORS 限制）。與 axios 的 baseURL 對齊：沒設 VITE_API_URL 時回空字串（打相對路徑，
 * 開發時由 vite proxy 的 /oauth2 轉到 :8080）；有設時回後端 origin（結尾不含斜線、
 * 不含 /api，見 .env.example）。LOGIN 與 LINK 都透過這裡組發起網址。
 *
 * @returns {string} 後端 origin（可能為空字串）
 */
export function oauthBackendBase() {
  return (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')
}

/**
 * Google 登入入口網址。整頁導向用（window.location.href = ...）。
 *
 * @param {string} [returnPath] 登入前所在的站內相對路徑，登入成功後導回。
 */
export function googleLoginUrl(returnPath) {
  const url = `${oauthBackendBase()}/oauth2/authorization/google`
  const clean = sanitizeReturnPath(returnPath)
  return clean ? `${url}?returnPath=${encodeURIComponent(clean)}` : url
}

/** 後端 LINK 發起端點唯一允許回傳的相對路徑（pathname 精確比對，不接受前後綴變形） */
const LINK_AUTHORIZATION_PATHNAME = '/oauth2/authorization/google'

/**
 * 把後端 LINK 發起 API 回傳的相對路徑組成可導向的完整網址。
 *
 * <p>後端 {@code POST /api/account/me/oauth/google/link} 回的是
 * {@code /oauth2/authorization/google?transaction=<opaque>}（相對路徑）。這個值最終會交給
 * {@code window.location.href}，所以做**精確白名單**驗證（open redirect 縱深防禦，
 * 即使值來源是我們自己的後端也不放寬）：
 * <ul>
 *   <li>解析後 pathname 必須精確等於 {@code /oauth2/authorization/google}
 *       （擋掉 {@code /oauth2/authorization/google-evil}、{@code .../google/extra}）</li>
 *   <li>query 只能有一個 {@code transaction}，且值非空</li>
 *   <li>不得帶 fragment、不得有重複參數或其他 query</li>
 *   <li>不得是絕對 URL／protocol-relative（跨域）</li>
 * </ul>
 * 任一不符回 {@code null}，呼叫端據此就地顯示錯誤、不導頁。通過後才用
 * {@link oauthBackendBase} 補上後端 origin。
 *
 * @param {unknown} relativeUrl 後端回傳的 authorizationUrl
 * @returns {string|null} 合法則回完整可導向網址，否則 null
 */
export function linkAuthorizationUrl(relativeUrl) {
  if (typeof relativeUrl !== 'string') return null
  const trimmed = relativeUrl.trim()
  // 必須是站內絕對路徑；protocol-relative（//、/\）與含反斜線一律拒絕
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.includes('\\')) return null

  let parsed
  try {
    // base 只用來讓相對路徑可被 URL 解析；解析後所有欄位都會逐項比對，base 本身不影響判定
    parsed = new URL(trimmed, 'http://oauth-link.invalid')
  } catch {
    return null
  }
  if (parsed.pathname !== LINK_AUTHORIZATION_PATHNAME) return null
  if (parsed.hash !== '') return null

  const params = [...parsed.searchParams.keys()]
  if (params.length !== 1 || params[0] !== 'transaction') return null
  if (!parsed.searchParams.get('transaction')) return null

  return `${oauthBackendBase()}${parsed.pathname}${parsed.search}`
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
