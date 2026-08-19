import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import {
  getCart,
  addCartItem,
  updateCartItem,
  removeCartItem,
  clearCart as apiClearCart,
} from '@/api/cart.js'

export const useCartStore = defineStore('cart', () => {
  const items = ref([])          // CartItemResponse 列表
  const totalAmount = ref(0)     // 後端算好的購物車總額
  const loading = ref(false)

  const itemCount = computed(() =>
    items.value.reduce((sum, item) => sum + Number(item.quantity), 0)
  )

  // 從後端載入購物車；未登入時直接清空本地狀態，不打 API
  async function loadCart() {
    const auth = useAuthStore()
    if (!auth.isLoggedIn) {
      items.value = []
      totalAmount.value = 0
      return
    }
    loading.value = true
    try {
      const res = await getCart()
      items.value = res.data.items
      totalAmount.value = res.data.totalAmount
    } finally {
      loading.value = false
    }
  }

  async function addItem(productId, quantity = 1) {
    try {
      await addCartItem(productId, quantity)
      await loadCart()
      return { success: true }
    } catch (err) {
      return { success: false, message: err.message || '加入購物車失敗' }
    }
  }

  async function updateItem(cartItemId, quantity) {
    try {
      await updateCartItem(cartItemId, quantity)
      await loadCart()
      return { success: true }
    } catch (err) {
      return { success: false, message: err.message || '修改數量失敗' }
    }
  }

  async function removeItem(cartItemId) {
    try {
      await removeCartItem(cartItemId)
      await loadCart()
      return { success: true }
    } catch (err) {
      return { success: false, message: err.message || '刪除品項失敗' }
    }
  }

  async function clearCart() {
    try {
      await apiClearCart()
      items.value = []
      totalAmount.value = 0
      return { success: true }
    } catch (err) {
      return { success: false, message: err.message || '清空購物車失敗' }
    }
  }

  // 登出時清空本地狀態，不打 API
  function reset() {
    items.value = []
    totalAmount.value = 0
  }

  return {
    items, totalAmount, loading, itemCount,
    loadCart, addItem, updateItem, removeItem, clearCart, reset,
  }
})
