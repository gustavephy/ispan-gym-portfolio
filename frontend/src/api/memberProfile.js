import api from './axios.instance.js'

// 會員自助查詢個人資料，對應後端 MemberProfileController 的 GET /api/member/profile
export const getMyProfile = () => api.get('/api/member/profile')

/**
 * 會員自助修改個人資料，對應 PUT /api/member/profile（MemberProfileUpdateRequest）。
 * PUT 是完整覆蓋語意：firstName/lastName/phone 必填，其餘選填欄位傳 null 代表清除，
 * 不是「只送有改的欄位」。email／avatarUrl／status 是唯讀欄位，不接受從這裡修改。
 */
export const updateMyProfile = (payload) => api.put('/api/member/profile', payload)
