import api from './axios.instance.js'

// 管理端查詢指定會員的私教課預約歷史，對應 AdminMemberController 的
// coach-bookings 端點，需 ADMIN 角色。回傳 PageResponse（分頁），
// 與會員端 /api/member/bookings 共用同一套後端查詢。
// 會員查自己的預約歷史請用 @/api/coachBooking.js 的 getMyBookings。

/**
 * 分頁查詢指定會員的所有私教課預約歷史，可依狀態與上課日期區間篩選
 * @param {number} memberId 要查詢的會員 id
 * @param {{page?: number, size?: number, status?: string, dateFrom?: string, dateTo?: string}} params
 *   page 從 0 開始；status 為 PENDING/COMPLETED/CANCELLED；dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getMemberCoachBookings = (
  memberId,
  { page = 0, size = 10, status = '', dateFrom = '', dateTo = '' } = {},
) =>
  api.get(`/api/admin/members/${memberId}/coach-bookings`, {
    params: { page, size, status: status || undefined, dateFrom: dateFrom || undefined, dateTo: dateTo || undefined },
  })
