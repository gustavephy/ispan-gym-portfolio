import api from './axios.instance.js'

// 私教課額度查詢，對應後端 MemberCourseQuotaController／AdminMemberController 的
// course-quotas 端點。純唯讀查詢，不涉及任何交易動作。

/**
 * 分頁查詢自己的私教課額度，需登入且需 MEMBER 角色
 * @param {{page?: number, size?: number}} params page 從 0 開始
 */
export const getMyQuotas = ({ page = 0, size = 10 } = {}) =>
  api.get('/api/member/course-quotas', { params: { page, size } })

/**
 * 管理端查詢指定會員的私教課額度，需 ADMIN 角色
 * @param {number} memberId 要查詢的會員 id
 * @param {{page?: number, size?: number}} params page 從 0 開始
 */
export const getMemberQuotas = (memberId, { page = 0, size = 10 } = {}) =>
  api.get(`/api/admin/members/${memberId}/course-quotas`, { params: { page, size } })
