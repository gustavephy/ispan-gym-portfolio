import api from './axios.instance.js'
import { useAuthStore } from '@/stores/auth.js'

// 查詢目前可購買的訂閱方案（公開，未登入也可查看）
export const getSubscriptionPlans = () => api.get('/api/subscription-plans')

/**
 * 查詢自己名下最新一筆訂閱紀錄。呼叫端把這支當成「可選的軟性查詢」——查不到就當沒有訂閱，
 * 不該影響整頁載入，更不該把使用者踢出登入狀態。兩道保護：
 *
 * <p>1. 非會員一律不發請求，直接回 {@code { data: null }}。角色是疊加的，「已登入」不等於
 * 「是會員」——純 Employee/Admin 名下沒有 member 列，後端 requireCurrentMemberId() 會回 401，
 * 而攔截器把 401 當成 session 過期，會清掉 token 並導向登入頁，讓剛登入的管理者一進首頁就被登出。
 * 這道判斷放在這裡而不是各個頁面，是因為先前三個呼叫端各自漏掉過
 *（見 HomeCourses／CourseView／SubscriptionView），收在同一個出口才不會有人再忘記。
 *
 * <p>2. skipAuthRedirect：萬一仍收到 401（例如會員在查詢途中 token 剛好過期），
 * 也不讓這支軟性查詢單方面觸發全域登出，交由後續其他請求去反映真正的登入失效。
 *
 * <p>skipGlobalError：會員尚無任何訂閱紀錄時後端回 404，那是預期中的狀態
 *（新註冊會員本來就沒訂閱過），不該跳全域錯誤提示。
 *
 * @returns {Promise<{ data: object|null }>} 非會員或查無紀錄時 data 為 null
 */
export const getMySubscription = async () => {
  try {
    const auth = useAuthStore()
    if (!auth.isLoggedIn || !auth.isMember) {
      return { data: null }
    }
  } catch (e) {
    // Pinia 尚未初始化時取不到 store，一律當成非會員不發請求
    //（比照 axios.instance.js 的 request 攔截器對同一風險的處理）
    return { data: null }
  }
  return api.get('/api/me/subscription', { skipGlobalError: true, skipAuthRedirect: true })
}
