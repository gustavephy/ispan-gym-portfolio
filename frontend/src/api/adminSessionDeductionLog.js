import api from './axios.instance.js'

// 管理端查詢指定會員的私教課堂數異動稽核紀錄，對應 AdminMemberController 的
// deduction-logs 端點，需 ADMIN 角色。回傳 PageResponse（分頁）。

/**
 * 分頁查詢指定會員的所有堂數異動稽核紀錄，可依事件類型與建立時間區間篩選
 * @param {number} memberId 要查詢的會員 id
 * @param {{page?: number, size?: number, eventType?: string, dateFrom?: string, dateTo?: string}} params
 *   page 從 0 開始；eventType 為 BOOK/CHECKIN/CANCEL_REFUND/CANCEL_CONSUME/CREDIT；dateFrom/dateTo 為 yyyy-MM-dd
 */
export const getMemberDeductionLogs = (
  memberId,
  { page = 0, size = 10, eventType = '', dateFrom = '', dateTo = '' } = {},
) =>
  api.get(`/api/admin/members/${memberId}/deduction-logs`, {
    params: {
      page,
      size,
      eventType: eventType || undefined,
      dateFrom: dateFrom || undefined,
      dateTo: dateTo || undefined,
    },
  })
