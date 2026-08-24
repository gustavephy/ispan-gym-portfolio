import api from './axios.instance.js'

/**
 * 這裡的每一支都是匿名端點（未登入狀態呼叫）：
 *   - skipAuthRedirect：401（例如帳密錯誤）屬於正常的表單結果，不該被攔截器
 *     當成「token 失效」處理，不能清掉既有登入狀態或導去登入頁。
 *   - skipGlobalError：錯誤要由呼叫端的表單就地顯示，不跳全域錯誤提示條。
 */
const ANONYMOUS_CONFIG = { skipAuthRedirect: true, skipGlobalError: true }

/**
 * POST /api/auth/login
 * 回應 LoginResponse: { accessToken, accountId, email, roles }
 * roles 為去掉 ROLE_ 前綴的清單，如 ["MEMBER"]、["EMPLOYEE","ADMIN"]，僅供前端導流用。
 */
export const login = (credentials) => api.post('/api/auth/login', credentials, ANONYMOUS_CONFIG)

/** POST /api/members/register — 回 201，body 為 { email, message } */
export const registerMember = (payload) => api.post('/api/members/register', payload, ANONYMOUS_CONFIG)

/** POST /api/employees/register — 回 201，員工需等待人工審核 */
export const registerEmployee = (payload) =>
  api.post('/api/employees/register', payload, ANONYMOUS_CONFIG)

/** POST /api/employees/reapply — 僅 REJECTED 的申請可用，以 email+密碼證明身分 */
export const reapplyEmployee = (payload) => api.post('/api/employees/reapply', payload, ANONYMOUS_CONFIG)

/** GET /api/accounts/verify-email?token= — 回 { purpose: 'REGISTRATION' | 'REACTIVATION' } */
export const verifyEmail = (token) =>
  api.get('/api/accounts/verify-email', { params: { token }, ...ANONYMOUS_CONFIG })

/** POST /api/accounts/resend-verification-email — 回 202，訊息固定（反帳號列舉） */
export const resendVerificationEmail = (email) =>
  api.post('/api/accounts/resend-verification-email', { email }, ANONYMOUS_CONFIG)

/** POST /api/auth/forgot-password — 回 202，訊息固定（反帳號列舉，不論帳號是否存在／是否為OAuth-only） */
export const forgotPassword = (email) => api.post('/api/auth/forgot-password', { email }, ANONYMOUS_CONFIG)

/**
 * POST /api/auth/reset-password — 成功回 204（無 body）
 * 失敗一律 400 RESET_TOKEN_INVALID（不存在／已使用／已作廢／已過期／帳號已不符資格皆同一訊息）
 */
export const resetPassword = (payload) => api.post('/api/auth/reset-password', payload, ANONYMOUS_CONFIG)
