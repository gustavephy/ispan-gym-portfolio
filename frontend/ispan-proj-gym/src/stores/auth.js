import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 登入狀態 store。token 同步存到 localStorage，重新整理頁面後仍保持登入。
 * axios 攔截器（@/api/axios.instance.js）從這裡取 token 附到 Authorization header，
 * 收到 401 時也會呼叫這裡的 logout()。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')

  function setToken(newToken) {
    token.value = newToken
    localStorage.setItem('token', newToken)
  }

  function logout() {
    token.value = ''
    localStorage.removeItem('token')
  }

  return { token, setToken, logout }
})
