import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getProductCategories,
  getProducts,
  getProductById,
  getProductComments,
  submitProductComment,
} from '@/api/product/product.js'

/**
 * 商品／課程瀏覽相關資料的 store。
 * 各 load 動作失敗時不在這裡攔截——axios 攔截器已統一發出全域錯誤事件，
 * 呼叫端如需額外處理（例如顯示區塊內的錯誤狀態）再自行 catch。
 */
export const useProductsStore = defineStore('products', () => {
  const categories = ref([])
  const products = ref([])
  const totalElements = ref(0)
  const totalPages = ref(0)
  const currentProduct = ref(null)
  const comments = ref([])
  const loading = ref(false)

  //載入商品分類
  async function loadCategories() {
    try {
      const res = await getProductCategories()
      categories.value = (res.data || []).map(cat => ({
        ...cat,
        id: cat.productCategoryId || cat.id
      }))
    } catch (e) { throw e; }
  }

  //載入商品列表，params 直接透傳給後端（分類、關鍵字、分頁等）
  //後端回傳 Spring Data Page（{ content, totalElements, totalPages, ... }），這裡拆開存
  async function loadProducts(params) {
    loading.value = true
    try {
      const queryParams = {
        page: params.page ?? 0,
        size: params.size ?? 12,
        productCategoryId: params.productCategoryId || undefined,
        keyword: params.keyword || undefined,
        brand: params.brand || undefined,
        flavor: params.flavor || undefined,
        productSize: params.productSize || undefined,
        minPrice: params.minPrice ?? undefined,
        maxPrice: params.maxPrice ?? undefined
      }
      const res = await getProducts(queryParams)
      products.value = res.data.content || []
      totalPages.value = res.data.totalPages || 1
      totalElements.value = res.data.totalElements || 0
    } catch (e) {
      products.value = []
      totalPages.value = 1
      totalElements.value = 0
      throw e;
    } finally {
      loading.value = false
    }
  }

  //載入單一商品明細與留言
  //留言 API 後端尚未實作，失敗時不影響商品明細顯示
  async function loadProduct(productId) {
    // 先清空舊資料：不清的話，從 A 商品切到 B 商品時模板的 v-if="product"
    // 仍成立（留著 A 的資料），畫面會先閃一下 A 才換成 B，spinner 也永遠不會出現
    currentProduct.value = null
    comments.value = []
    loading.value = true
    try {
      const res = await getProductById(productId)
      currentProduct.value = res.data ? {
        ...res.data,
        id: res.data.productId || res.data.id
      } : null
      try {
        const commentRes = await getProductComments(productId)
        comments.value = commentRes.data
      } catch {
        comments.value = []
      }
    } catch (e) {
      currentProduct.value = null
      throw e;
    } finally {
      loading.value = false
    }
  }

  // 載入商品評論
  // @param {number|string} productId 商品 ID
  async function loadComments(productId) {
    try {
      const res = await getProductComments(productId)
      comments.value = res.data || [] // 儲存該商品的所有用戶評價
    } catch (e) {
      throw e;
      comments.value = []
    }
  }

  // 送出商品評論
  // @param {number|string} productId 商品 ID
  // @param {object} commentData 評論的資料（如評分、文字內容）
  async function submitComment(productId, commentData) {
    try {
      await submitProductComment({ productId, ...commentData })
      await loadComments(productId) // 發表成功後，重新整理當前的評論列表
      return { success: true }
    } catch (e) {
      throw e;
    }
  }

  return {
    categories,
    products,
    totalElements,
    totalPages,
    currentProduct,
    comments,
    loading,
    loadCategories,
    loadProducts,
    loadProduct,
    submitComment,
    loadComments
  }
})
