import api from './axios.instance.js'

/**
 * POST /api/auth/login
 * 回應 LoginResponse: { accessToken, accountId, email, roles }
 * roles 為去掉 ROLE_ 前綴的清單，如 ["MEMBER"]、["EMPLOYEE","ADMIN"]，僅供前端導流用。
 */
export const login = (credentials) => api.post('/api/auth/login', credentials)

/** POST /api/members/register — 回 201，body 為 { email, message } */
export const registerMember = (payload) => api.post('/api/members/register', payload)

/** POST /api/employees/register — 回 201，員工需等待人工審核 */
export const registerEmployee = (payload) => api.post('/api/employees/register', payload)

/** POST /api/employees/reapply — 僅 REJECTED 的申請可用，以 email+密碼證明身分 */
export const reapplyEmployee = (payload) => api.post('/api/employees/reapply', payload)

/** GET /api/accounts/verify-email?token= — 回 { purpose: 'REGISTRATION' | 'REACTIVATION' } */
export const verifyEmail = (token) => api.get('/api/accounts/verify-email', { params: { token } })

/** POST /api/accounts/resend-verification-email — 回 202，訊息固定（反帳號列舉） */
export const resendVerificationEmail = (email) =>
  api.post('/api/accounts/resend-verification-email', { email })
