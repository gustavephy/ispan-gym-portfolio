import api from './axios.instance.js'

// 管理員維護團課主檔，對應後端 AdminGroupCourseController 的
// GET/POST /api/admin/group-courses。跟教練/會員視角的團課 API 分開檔案，職責不同。

/** 查詢所有團課主檔，不分狀態，需登入且為 ADMIN */
export const getAllCourses = () => api.get('/api/admin/group-courses')

/**
 * 建立團課主檔，需登入且為 ADMIN
 * @param {{name: string, trainerId: number, description?: string, imageUrl?: string, isTrial?: boolean, status?: string, maxCapacity: number, courseCategoryId?: number}} payload
 */
export const createCourse = (payload) => api.post('/api/admin/group-courses', payload)

/**
 * 下架團課主檔，需登入且為 ADMIN。底層是把 status 改成 INACTIVE，不是真的刪除資料
 * @param {number} id 要下架的課程主檔 id
 */
export const deactivateCourse = (id) => api.delete(`/api/admin/group-courses/${id}`)
