import api from '../axios.instance.js'

// 取得課程的所有分類清單
export const getCourseCategories = () => api.get('/api/course-categories')

// 取得公開的團課課程列表 (前台預約用)
export const getGroupCoursesPublic = (params) => api.get('/api/group-courses', { params })

// 取得公開的私人教練課程列表 (方案購買展示用)
export const getPrivateCoursesPublic = (params) => api.get('/api/private-courses', { params })