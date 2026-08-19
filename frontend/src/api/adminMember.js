import api from './axios.instance.js'

// 管理員維護會員資料，對應後端 AdminMemberController 的 /api/admin/members。
// 跟會員自助的 memberProfile 職責分開：那是會員改自己的資料，這裡是管理員代改別人的。

/**
 * 分頁查詢會員，需登入且為 ADMIN。
 * 回應是 Spring 的 Page 結構（content／totalElements／totalPages），
 * 不是專案自訂的 PageResponse（items／totalItems），呼叫端取值時注意差異。
 * @param {{keyword?: string, status?: string, page?: number, size?: number,
 *          sort?: string, direction?: string}} params
 *   keyword 不分大小寫比對姓名／電話／登入 email；status 省略代表全部；
 *   page 從 0 開始；size 上限 100；sort 僅接受 id／name／createdAt（其餘退回 id）
 */
export const getMembers = ({
  keyword = '',
  status = '',
  page = 0,
  size = 10,
  sort = '',
  direction = '',
} = {}) =>
  api.get('/api/admin/members', {
    // 空字串代表「不篩選」，轉成 undefined 讓 axios 直接不帶這個 query，
    // 否則後端會收到 status='' 而在 enum 轉換時回 400
    params: {
      keyword: keyword || undefined,
      status: status || undefined,
      page,
      size,
      sort: sort || undefined,
      direction: direction || undefined,
    },
  })

/**
 * 查單一會員的完整資料（含地址、緊急聯絡人等 PII，列表刻意不回傳這些），需 ADMIN
 * @param {number} memberId
 */
export const getMemberById = (memberId) => api.get(`/api/admin/members/${memberId}`)

/**
 * 管理員代改會員資料，需 ADMIN。不含 email 與 status——
 * email 屬 Account 域不由此變更，status 走 suspend／activate 兩支專用端點。
 * @param {number} memberId
 * @param {{firstName: string, lastName: string, phone: string, dob?: string,
 *          gender?: string, emergencyContact?: string, emergencyPhone?: string,
 *          zipCode?: string, city?: string, district?: string, address?: string}} payload
 */
export const updateMember = (memberId, payload) =>
  api.put(`/api/admin/members/${memberId}`, payload)

/**
 * 停權會員，需 ADMIN。已是 SUSPENDED 或仍為 PENDING（未完成 Email 驗證）會回 409。
 * @param {number} memberId
 */
export const suspendMember = (memberId) => api.post(`/api/admin/members/${memberId}/suspend`)

/**
 * 恢復會員，需 ADMIN。已是 ACTIVE 或仍為 PENDING 會回 409。
 * @param {number} memberId
 */
export const activateMember = (memberId) => api.post(`/api/admin/members/${memberId}/activate`)
