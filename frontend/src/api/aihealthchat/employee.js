import api from '../axios.instance.js'

/** 目前登入員工自己的 employee_id，聊天室教練端組裝 room_id（member_{id}_employee_{myId}）時需要 */
export const getMyEmployeeId = () => api.get('/api/employees/me')
