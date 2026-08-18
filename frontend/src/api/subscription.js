import api from './axios.instance.js'

// 查詢目前可購買的訂閱方案（公開，未登入也可查看）
export const getSubscriptionPlans = () => api.get('/api/subscription-plans')

// 查詢自己名下最新一筆訂閱紀錄；尚無任何紀錄時後端回 404，這是正常情況，不觸發全域錯誤提示
export const getMySubscription = () => api.get('/api/me/subscription', { skipGlobalError: true })
