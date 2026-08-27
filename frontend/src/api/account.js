import api from './axios.instance.js'

/**
 * 帳號層級的自助查詢與操作：身份快照、修改密碼、Google 綁定狀態、全裝置登出。
 * 三支後端 Controller（AccountController／PasswordController／OAuthConnectionController）
 * 共用同一個 /api/account/me 路徑前綴，前端集中放在這一個檔案。
 *
 * 都帶 skipGlobalError：錯誤要由帳號安全頁就地顯示，不跳全域錯誤提示條——比照
 * HomeCourses.vue 對 /api/me/subscription 的做法。故意不帶 skipAuthRedirect：
 * token 真的過期時仍要走一般的 401 流程（清登入狀態、導回登入頁），這幾支都是
 * 已登入狀態下才會呼叫的端點，跟匿名流程的語意不同。
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
 * GET /api/account/me/oauth-connections
 * 回應 OAuthConnectionsResponse: { google: { linked, email, linkedAt }, hasPassword }
 * hasPassword 是登入方式狀態的唯一來源，不在 AccountMeResponse 裡重複一份。
 */
export const getOAuthConnections = () => api.get('/api/account/me/oauth-connections', CONFIG)

/**
 * POST /api/account/me/logout-all
 * 成功回 204（無 body），不核發新 token——連呼叫者這次請求所用的 token 也會失效，
 * 呼叫端收到成功後要自行呼叫 auth store 的 logout() 並導去登入頁。
 */
export const logoutAllDevices = () => api.post('/api/account/me/logout-all', null, CONFIG)
