import api from './axios.instance.js'

// 管理端查詢指定會員的團課報名紀錄，對應 AdminMemberController 的
// group-class-bookings 端點，需 ADMIN 角色。回傳 PageResponse（分頁），
// 與會員端 /api/member/group-classes/bookings 共用同一套後端查詢。
// 會員查自己的報名紀錄請用 @/api/groupClass.js 的 getMyBookings。

/**
 * 分頁查詢指定會員的所有團課報名紀錄，可依課程名稱或教室搜尋、依狀態與開課日期區間篩選
 * @param {number} memberId 要查詢的會員 id
 * @param {{page?: number, size?: number, search?: string, status?: string, dateFrom?: string, dateTo?: string}} params
 *   page 從 0 開始；status 為 BOOKED/CANCELLED；dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getMemberGroupClassBookings = (
  memberId,
  { page = 0, size = 10, search = '', status = '', dateFrom = '', dateTo = '' } = {},
) =>
  api.get(`/api/admin/members/${memberId}/group-class-bookings`, {
    params: { page, size, search, status: status || undefined, dateFrom: dateFrom || undefined, dateTo: dateTo || undefined },
  })
