import api from '../axios.instance.js'

//商品分類
export const adminCreateProductCategory = (data) => api.post('/api/admin/product-categories', data)
export const adminUpdateProductCategory = (id, data) => api.put(`/api/admin/product-categories/${id}`, data)
export const adminDeleteProductCategory = (id) => api.delete(`/api/admin/product-categories/${id}`)


//商品
// 建立商品
export const adminCreateProduct = (data) => api.post('/api/admin/products', data)
// 編輯商品
export const adminUpdateProduct = (id, data) => api.put(`/api/admin/products/${id}`, data)
// 刪除商品
export const adminDeleteProduct = (id) => api.delete(`/api/admin/products/${id}`)
// 上傳圖片
export const adminUploadProductImage = (formData, config) => api.post('/api/admin/products/upload', formData, config)
