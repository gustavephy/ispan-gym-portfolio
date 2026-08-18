import api from './axios.instance.js'

// 管理員維護團課主檔，對應後端 AdminGroupCourseController 的
// GET/POST /api/admin/group-courses。跟教練/會員視角的團課 API 分開檔案，職責不同。

/**
 * 分頁查詢團課主檔，不分狀態，可依課程名稱或教練姓名搜尋，需登入且為 ADMIN。
 * 回傳格式為 { items, page, size, totalItems, totalPages }，不再是純陣列。
 * @param {{page?: number, size?: number, search?: string}} params page 從 0 開始
 */
export const getAllCourses = ({ page = 0, size = 10, search = '' } = {}) =>
  api.get('/api/admin/group-courses', { params: { page, size, search } })

/**
 * 建立團課主檔，需登入且為 ADMIN
 * @param {{name: string, trainerId: number, description?: string, imageUrl?: string, isTrial?: boolean, status?: string, maxCapacity: number, courseCategoryId?: number}} payload
 */
export const createCourse = (payload) => api.post('/api/admin/group-courses', payload)

/**
 * 修改團課主檔，需登入且為 ADMIN。不含 trainerId——授課教練不可透過這支 API 變更，
 * 換教練需下架後另建新課程。description/imageUrl/isTrial/courseCategoryId 留空代表維持原值，不會被清空
 * @param {number} id 要修改的課程主檔 id
 * @param {{name: string, description?: string, imageUrl?: string, isTrial?: boolean, maxCapacity: number, courseCategoryId?: number}} payload
 */
export const updateCourse = (id, payload) => api.put(`/api/admin/group-courses/${id}`, payload)

/**
 * 下架團課主檔，需登入且為 ADMIN。底層是把 status 改成 INACTIVE，不是真的刪除資料
 * @param {number} id 要下架的課程主檔 id
 */
export const deactivateCourse = (id) => api.delete(`/api/admin/group-courses/${id}`)
