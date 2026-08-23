import api from "../axios.instance.js"

// 取得商品分類
export const getProductCategories = () => api.get('/api/product-categories')
// 取得所有商品
export const getProducts = (params) => api.get('/api/products', { params })
// 取得商品 by Product ID
export const getProductById = (id) => api.get(`/api/products/${id}`)
// 取得商品comment by Product ID
export const getProductComments = (productId) => api.get(`/api/products/${productId}/comments`)
// 建立商品Comment
export const submitProductComment = (commentData) => api.post(`/api/comments`, commentData)