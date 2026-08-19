import api from '../axios.instance.js'

/** 目前登入會員自己的 member_id，聊天室組裝 room_id（member_{id}_ai 等）時需要 */
export const getMyMemberId = () => api.get('/api/members/me')
