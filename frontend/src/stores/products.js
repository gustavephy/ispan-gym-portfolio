import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getProductCategories,
  getCourseCategories,
  getProducts,
  getTrainerCoursesPublic,
  getProductById,
  getProductComments,
  submitProductComment,
} from '@/api/product.js'

/**
 * 商品／課程瀏覽相關資料的 store。
 * 各 load 動作失敗時不在這裡攔截——axios 攔截器已統一發出全域錯誤事件，
 * 呼叫端如需額外處理（例如顯示區塊內的錯誤狀態）再自行 catch。
 */
export const useProductsStore = defineStore('products', () => {
  const categories = ref([])
  const courseCategories = ref([])
  const products = ref([])
  const totalElements = ref(0)
  const totalPages = ref(0)
  const currentProduct = ref(null)
  const comments = ref([])
  const trainerCourses = ref([])
  const loading = ref(false)

  //載入商品分類
  async function loadCategories() {
    const res = await getProductCategories()
    categories.value = res.data
  }

  //載入課程分類
  async function loadCourseCategories() {
    const res = await getCourseCategories()
    courseCategories.value = res.data
  }

  //載入商品列表，params 直接透傳給後端（分類、關鍵字、分頁等）
  //後端回傳 Spring Data Page（{ content, totalElements, totalPages, ... }），這裡拆開存
  async function loadProducts(params) {
    loading.value = true
    try {
      const res = await getProducts(params)
      products.value = res.data.content
      totalElements.value = res.data.totalElements
      totalPages.value = res.data.totalPages
    } finally {
      loading.value = false
    }
  }

  //載入公開的教練課程列表
  async function loadTrainerCourses() {
    const res = await getTrainerCoursesPublic()
    trainerCourses.value = res.data
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
      currentProduct.value = res.data
      try {
        const commentRes = await getProductComments(id)
        comments.value = commentRes.data
      } catch {
        comments.value = []
      }
    } finally {
      loading.value = false
    }
  }

  //送出商品留言後重新載入該商品的留言列表
  async function submitComment(commentData) {
    await submitProductComment(commentData)
    if (commentData.productId != null) {
      const res = await getProductComments(commentData.productId)
      comments.value = res.data
    }
  }

  return {
    categories,
    courseCategories,
    products,
    totalElements,
    totalPages,
    currentProduct,
    comments,
    trainerCourses,
    loading,
    loadCategories,
    loadCourseCategories,
    loadProducts,
    loadTrainerCourses,
    loadProductDetail,
    submitComment,
  }
})
