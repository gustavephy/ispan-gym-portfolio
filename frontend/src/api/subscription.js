import api from './axios.instance.js'

// 查詢目前可購買的訂閱方案（公開，未登入也可查看）
export const getSubscriptionPlans = () => api.get('/api/subscription-plans')

// 查詢自己名下最新一筆訂閱紀錄；尚無任何紀錄時後端回 404，這是正常情況，不觸發全域錯誤提示。
// 後端以 requireCurrentMemberId() 反查 member 列，純員工／純管理員帳號（無 MEMBER 角色）
// 沒有對應會員資料時會回 401——skipAuthRedirect 避免這種「已登入但非會員」的情況被
// axios 攔截器當成 token 過期而觸發全域登出（呼叫端本來就是把這支當成可選的軟性查詢）。
export const getMySubscription = () =>
  api.get('/api/me/subscription', { skipGlobalError: true, skipAuthRedirect: true })
