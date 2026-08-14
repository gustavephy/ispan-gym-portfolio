import api from './axios-instance.js'

export const getProductCategories = ()=>api.get('/api/product-categories')
export const getCourseCategories = ()=>api.get('/api/course-categories')
export const getProducts = (params) => api.get('/api/products', {params})
export const getTrainerCoursesPublic = () => api.get('/api/trainer/courses/public')
export const getProductById = (id) => api.get(`/api/products/${id}`)
export const getProductComments = (productId) => api.get(`/api/products/${productId}/comments`)
export const submitProductComment = (commentData) => api.post('/api/comments', commentData)