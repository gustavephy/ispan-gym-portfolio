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
  async function loadProducts(params) {
    loading.value = true
    try {
      const res = await getProducts(params)
      products.value = res.data
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
  async function loadProductDetail(id) {
    loading.value = true
    try {
      const res = await getProductById(id)
      currentProduct.value = res.data
      const commentRes = await getProductComments(id)
      comments.value = commentRes.data
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
