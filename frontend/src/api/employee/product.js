import api from '../axios.instance.js'

//商品分類
export const employeeCreateProductCategory = (data) => api.post('/api/employee/product-categories', data)
export const employeeUpdateProductCategory = (id, data) => api.put(`/api/employee/product-categories/${id}`, data)
export const employeeDeleteProductCategory = (id) => api.delete(`/api/employee/product-categories/${id}`)


//商品
// 建立商品
export const employeeCreateProduct = (data) => api.post('/api/employee/products', data)
// 編輯商品
export const employeeUpdateProduct = (id, data) => api.put(`/api/employee/products/${id}`, data)
// 刪除商品
export const employeeDeleteProduct = (id) => api.delete(`/api/employee/products/${id}`)
// 上傳圖片
export const employeeUploadProductImage = (formData, config) => api.post('/api/employee/products/upload', formData, config)