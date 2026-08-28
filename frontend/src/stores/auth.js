import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

/**
 * 登入狀態 store。token 與身分資訊同步存到 localStorage，重新整理頁面後仍保持登入。
 * axios 攔截器（@/api/axios.instance.js）從這裡取 token 附到 Authorization header，
 * 收到 401 時也會呼叫這裡的 logout()。
 *
 * roles 來自登入回應，是去掉 ROLE_ 前綴的清單（如 ["EMPLOYEE","ADMIN"]），
 * 僅供前端決定選單與導向。真正的授權後端每個請求都會重新檢查，
 * 前端改這裡的值不會取得任何實際權限。
 */
const STORAGE_KEY = 'auth'

function readStored() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    // 內容毀損時視為未登入，避免整個 app 開不起來
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  // 相容舊版：先前只存了單獨的 token 鍵
  const stored = readStored() ?? { token: localStorage.getItem('token') || '' }

  const token = ref(stored.token || '')
  const accountId = ref(stored.accountId ?? null)
  const email = ref(stored.email || '')
  const roles = ref(stored.roles || [])

  const isLoggedIn = computed(() => Boolean(token.value))
  const isMember = computed(() => roles.value.includes('MEMBER'))
  const isEmployee = computed(() => roles.value.includes('EMPLOYEE'))
  const isAdmin = computed(() => roles.value.includes('ADMIN'))
  const isTrainer = computed(() => roles.value.includes('TRAINER'))

  function persist() {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: token.value,
        accountId: accountId.value,
        email: email.value,
        roles: roles.value,
      }),
    )
  }

  /** 以 LoginResponse 建立登入狀態 */
  function setSession(loginResponse) {
    token.value = loginResponse.accessToken
    accountId.value = loginResponse.accountId ?? null
    email.value = loginResponse.email || ''
    roles.value = loginResponse.roles || []
    persist()
    localStorage.removeItem('token') // 清掉舊版鍵，避免兩處不同步
  }

  /** 僅更新 token（舊介面，保留相容） */
  function setToken(newToken) {
    token.value = newToken
    persist()
  }

  /**
   * 用 GET /api/account/me 的最新結果覆蓋 accountId/email/roles，藉此刷新可能已經過期的
   * localStorage 快照（例如帳號在登入後被停權/復權、身分被調整）。刻意不動 token——
   * 這支後端端點不核發新 JWT，跟 setSession() 預期拿到一組全新 accessToken 不同。
   */
  function syncAccount(accountMeResponse) {
    accountId.value = accountMeResponse.accountId ?? null
    email.value = accountMeResponse.email || ''
    roles.value = accountMeResponse.roles || []
    persist()
  }

  function logout() {
    token.value = ''
    accountId.value = null
    email.value = ''
    roles.value = []
    localStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem('token')
  }

  return {
    token,
    accountId,
    email,
    roles,
    isLoggedIn,
    isMember,
    isEmployee,
    isAdmin,
    isTrainer,
    setSession,
    setToken,
    syncAccount,
    logout,
  }
})
