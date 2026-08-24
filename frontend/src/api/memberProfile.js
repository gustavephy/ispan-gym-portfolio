import api from './axios.instance.js'

// 會員自助查詢個人資料，對應後端 MemberProfileController 的 GET /api/member/profile
export const getMyProfile = () => api.get('/api/member/profile')
