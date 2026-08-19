import api from './axios.instance.js'

// 查詢目前登入會員的購物車（CartResponse：items + totalAmount）
export const getCart = () => api.get('/api/cart')

// 加入商品到購物車；同一商品已存在時後端會合併數量
export const addCartItem = (productId, quantity) =>
  api.post('/api/cart/items', { productId, quantity })

// 修改購物車品項數量
export const updateCartItem = (cartItemId, quantity) =>
  api.put(`/api/cart/items/${cartItemId}`, { quantity })

// 移除單一購物車品項
export const removeCartItem = (cartItemId) => api.delete(`/api/cart/items/${cartItemId}`)

// 清空購物車
export const clearCart = () => api.delete('/api/cart')
