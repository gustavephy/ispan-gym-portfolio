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

  //載入商品列表
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
        maxPrice: params.maxPrice ?? undefined,
        minWeight: params.minWeight ?? undefined,
        maxWeight: params.maxWeight ?? undefined,
        sort: params.sort || undefined,
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
  async function loadProductDetail(id) {
    // 先清空舊資料：不清的話，從 A 商品切到 B 商品時模板的 v-if="product"
    // 仍成立（留著 A 的資料），畫面會先閃一下 A 才換成 B，spinner 也永遠不會出現
    currentProduct.value = null
    comments.value = []
    loading.value = true
    try {
      const res = await getProductById(id)
      currentProduct.value = res.data ? {
        ...res.data,
        id: res.data.productId || res.data.id
      } : null
      // 暫時關閉留言功能以避免後端未開發留言 API 導致 404 報錯
      // try {
      //   const commentRes = await getProductComments(id)
      //   comments.value = commentRes.data || []
      // } catch (err) {
      //   comments.value = []
      // }
      comments.value = []
    } catch (e) {
      currentProduct.value = null
      throw e;
    } finally {
      loading.value = false
    }
  }

  // 載入商品評論
  async function loadComments(productId) {
    try {
      const res = await getProductComments(productId)
      comments.value = res.data || []
    } catch (e) {
      comments.value = []
      throw e;
    }
  }

  // 送出商品評論
  async function submitComment(productId, commentData) {
    try {
      await submitProductComment({ productId, ...commentData })
      await loadComments(productId)
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
    loadProductDetail,
    submitComment,
    loadComments
  }
})
