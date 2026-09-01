import api from './axios.instance.js'

/**
 * 帳號層級的自助查詢與操作：身份快照、修改密碼、Google 綁定狀態、全裝置登出。
 * 三支後端 Controller（AccountController／PasswordController／OAuthConnectionController）
 * 共用同一個 /api/account/me 路徑前綴，前端集中放在這一個檔案。
 *
 * 都帶 skipGlobalError：錯誤要由帳號安全頁就地顯示，不跳全域錯誤提示條——比照
 * HomeCourses.vue 對 /api/me/subscription 的做法。故意不帶 skipAuthRedirect：
 * token 真的過期時（401 UNAUTHENTICATED／無 body 的 HTTP_401）仍要走一般的 401 流程
 * （清登入狀態、導回登入頁）。
 *
 * Google 綁定／解除的密碼重新驗證失敗回 401 REAUTHENTICATION_REQUIRED，那個由
 * axios 攔截器特別放行（不清登入），交給呼叫端的密碼確認 Modal 就地顯示——
 * 見 api/api-error.js 的 CODE_REAUTHENTICATION_REQUIRED 與 axios.instance.js 的 401 分流。
 */
const CONFIG = { skipGlobalError: true }

/**
 * GET /api/account/me
 * 回應 AccountMeResponse: { accountId, email, roles, createdAt }
 * roles 是查詢當下即時算出來的，可能跟登入時存在 auth store 裡的舊值不同
 * （例如帳號後來被停權）——呼叫端通常要接著用 stores/auth.js 的 syncAccount() 覆蓋回去。
 */
export const getMyAccount = () => api.get('/api/account/me', CONFIG)

/**
 * POST /api/account/me/change-password
 * 回應 ChangePasswordResponse: { accessToken, accountId, email, roles }——
 * 附一組新 JWT，成功後要用它原地替換掉 auth store 裡的 token（呼叫端用 auth.setSession() 處理）。
 */
export const changePassword = (payload) => api.post('/api/account/me/change-password', payload, CONFIG)

/**
 * POST /api/account/me/set-password
 * body: { newPassword, confirmPassword }（沒有 currentPassword——OAuth-only 帳號沒有舊密碼）
 * 回應 SetPasswordResponse: { accessToken, accountId, email, roles }——附一組新 JWT，
 * 成功後要用它原地替換 auth store 的 token（auth.setSession()）。
 * 失敗：400 INVALID_REQUEST（密碼規則／確認密碼）、409 PASSWORD_ALREADY_CONFIGURED（已有密碼）。
 * 僅供 OAuth-only（connections.hasPassword === false）帳號使用。
 */
export const setPassword = (payload) => api.post('/api/account/me/set-password', payload, CONFIG)

/**
 * GET /api/account/me/oauth-connections
 * 回應 OAuthConnectionsResponse: { google: { linked, email, linkedAt }, hasPassword }
 * hasPassword 是登入方式狀態的唯一來源，不在 AccountMeResponse 裡重複一份。
 */
export const getOAuthConnections = () => api.get('/api/account/me/oauth-connections', CONFIG)

/**
 * POST /api/account/me/oauth/google/link
 * body: { currentPassword }（重新驗證目前密碼）
 * 回應 OAuthLinkInitiateResponse: { authorizationUrl }——相對路徑，前端用
 * api/oauth.js 的 linkAuthorizationUrl() 組成完整網址後整頁導向。
 * 失敗：400 INVALID_REQUEST、401 REAUTHENTICATION_REQUIRED（密碼錯誤／限流，不清登入）、
 *       404 NOT_FOUND（功能停用）、409 OAUTH_ALREADY_LINKED、429 OAUTH_LINK_RATE_LIMITED。
 */
export const linkGoogle = (currentPassword) =>
  api.post('/api/account/me/oauth/google/link', { currentPassword }, CONFIG)

/**
 * DELETE /api/account/me/oauth/google
 * body: { currentPassword }——DELETE 帶 body 必須用 axios 的 config.data，不能當第二參數。
 * 成功 204、無新 JWT：後端在同一交易內 credentialsVersion + 1，讓所有既有 JWT 失效，
 * 呼叫端收到成功後必須清登入狀態並導回登入頁。
 * 失敗：400 INVALID_REQUEST、401 REAUTHENTICATION_REQUIRED（密碼錯誤，不清登入）、
 *       404 NOT_FOUND（未綁定）、409 LAST_LOGIN_METHOD（沒有本機密碼，Google 是最後登入方式）。
 */
export const unlinkGoogle = (currentPassword) =>
  api.delete('/api/account/me/oauth/google', { ...CONFIG, data: { currentPassword } })

/**
 * POST /api/account/me/logout-all
 * 成功回 204（無 body），不核發新 token——連呼叫者這次請求所用的 token 也會失效，
 * 呼叫端收到成功後要自行呼叫 auth store 的 logout() 並導去登入頁。
 */
export const logoutAllDevices = () => api.post('/api/account/me/logout-all', null, CONFIG)
