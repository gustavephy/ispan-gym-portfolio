import api from './axios.instance.js'

// 管理端查詢指定會員的私教課額度，對應 AdminMemberController 的 course-quotas 端點，
// 需 ADMIN 角色。回傳 List（不分頁），與會員端 /api/member/quotas 共用同一套後端查詢。
// 會員查自己的額度請用 @/api/member/booking.js 的 getMyQuotas。
export const getMemberQuotas = (memberId) =>
  api.get(`/api/admin/members/${memberId}/course-quotas`)
