import api from './axios.instance.js'

export const getProductCategories = ()=>api.get('/api/product-categories')
export const getCourseCategories = ()=>api.get('/api/course-categories')
export const getProducts = (params) => api.get('/api/products', {params})
export const getTrainerCoursesPublic = () => api.get('/api/trainer/courses/public')
export const getProductById = (id) => api.get(`/api/products/${id}`)
// 商品評論後端尚未實作，會回 404。這是已知的「選配 API」，
// 失敗時不該跳全域錯誤提示——呼叫端（products store）已自行 catch 成空陣列，
// 商品明細照常顯示。後端補上評論功能後移除 skipGlobalError 即可。
export const getProductComments = (productId) =>
  api.get(`/api/products/${productId}/comments`, { skipGlobalError: true })
export const submitProductComment = (commentData) => api.post('/api/comments', commentData)