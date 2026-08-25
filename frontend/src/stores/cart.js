// 錯誤訊息一律讀 err.message：axios 攔截器 reject 的是 normalizeApiError() 正規化後的物件，
// 沒有 response 欄位，寫成 err.response.data.message 會永遠拿到 undefined 而退回通用訊息。
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

  const itemCount = computed(() => items.value.length)

  // 從後端載入購物車；未登入、或登入者沒有會員身分時直接清空本地狀態，不打 API。
  //
  // 購物車是會員域的功能，後端以 requireCurrentMemberId() 反查 member 列，帳號沒有
  // 對應會員資料時會回 401「找不到登入資訊對應的會員帳號」。純員工／純管理員帳號
  // （roles 只有 EMPLOYEE／ADMIN）正是這種情況，而 axios 攔截器看到 401 會直接登出
  // 並跳「登入已過期」——使用者剛登入成功就被踢出去，看起來像是登入失敗。
  // 因此這裡先檢查 isMember，沒有會員身分就不發這個請求。
  async function loadCart() {
    const auth = useAuthStore()
    if (!auth.isLoggedIn || !auth.isMember) {
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
