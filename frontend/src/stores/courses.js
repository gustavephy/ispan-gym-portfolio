import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getPrivateCoursesPublic,
  getGroupCoursesPublic,
  getCourseCategories
} from '@/api/course/course.js'

// [全域狀態庫] useCoursesStore (courses)
// 定義課程相關的 Pinia 狀態庫，提供課程方案與教練課程的資料管理功能。
export const useCoursesStore = defineStore('courses', () => {
  const categories = ref([])     // 課程商品方案列表（用於課程商城或方案介紹）
  const privateCourses = ref([])  // 私教課方案列表（用於課程商城或方案介紹）
  const groupCourses = ref([])  // 團課方案列表（用於課程商城或方案介紹）
  const loading = ref(false)      // 課程 API 載入狀態

  // 響應式分頁狀態 (前端分頁使用)
  const currentPageNum = ref(0)
  const totalSize = ref(8)
  const searchQuery = ref('')

  // 記錄上一次發送請求的篩選參數以判斷是否需要重新請求 API
  const lastParams = ref({ keyword: null, trainerId: null, categoryId: null })

  //載入課程分類
  async function loadCategories() {
    const res = await getCourseCategories();
    categories.value = (res.data || []).map(c => ({
      ...c,
      id: c.courseCategoryId || c.id
    }))
  }

  // 計算屬性： 合併私教課和團課
  const allCourses = computed(() => {
    const privates = privateCourses.value.map(c => ({
      ...c,
      id: c.id || c.courseId,
      isPrivate: true
    }))
    const groups = groupCourses.value.map(c => ({
      ...c,
      id: c.id || c.courseId || c.groupCourseId,
      isPrivate: false
    }))
    return [...privates, ...groups]
  })

  // 計算屬性：搜尋篩選（私教課 + 團課）
  const filteredCourses = computed(() => {
    const keyword = searchQuery.value.trim().toLowerCase()
    if (!keyword) return allCourses.value
    return allCourses.value.filter(course => {
      const nameMatch = course.name?.toLowerCase().includes(keyword)
      const trainerMatch = course.trainer?.name?.toLowerCase().includes(keyword)
      return nameMatch || trainerMatch
    })
  })

  // 計算屬性：總筆數與總頁數 (自動隨 filteredCourses 計算)
  const courseTotalElements = computed(() => filteredCourses.value.length)
  const courseTotalPages = computed(() => Math.ceil(filteredCourses.value.length / totalSize.value) || 1)

  // 計算屬性：處理分頁邏輯
  const displayedCourses = computed(() => {
    const startIndex = currentPageNum.value * totalSize.value
    const endIndex = startIndex + totalSize.value
    return filteredCourses.value.slice(startIndex, endIndex)
  })

  //載入課程
  async function loadCourses(params = {}) {
    currentPageNum.value = params.page || 0
    totalSize.value = params.size || 8

    //判斷篩選欄位是否有值
    const hasFilterChanged =
      params.keyword !== lastParams.value.keyword ||
      params.trainerId !== lastParams.value.trainerId ||
      params.categoryId !== lastParams.value.categoryId

    if (hasFilterChanged || privateCourses.value.length === 0 && groupCourses.value.length === 0) {
      loading.value = true
      try {
        const [privateRes, groupRes] = await Promise.all([
          getPrivateCoursesPublic(),
          getGroupCoursesPublic()
        ])

        privateCourses.value = privateRes.data || []
        groupCourses.value = groupRes.data || []

        //儲存本次的篩選參數
        lastParams.value = {
          keyword: params.keyword || null,
          trainerId: params.trainerId || null,
          categoryId: params.categoryId || null
        }
      } finally {
        loading.value = false
      }
    }
  }

  // 統一前置驗證：依序檢查登入狀態與會員個人資料完整度
  async function validateMemberRequirements(authStore, showToast) {
    // 1. 檢查是否登入
    if (!authStore.isLoggedIn) {
      showToast('請先登入會員以進行課程購買', 'info')
      window.location.href = '/login?redirect=/products'
      return false
    }

    // 2. 檢查個人資料是否完整
    await authStore.checkProfile(true)
    if (!authStore.profileComplete) {
      showToast('購買前請先至個人中心填寫完整個人資訊（包含生日與手機號碼）！', 'warning')
      window.location.href = '/member'
      return false
    }

    return true
  }

  // 專門處理課程直接金流結帳，而不經過實體購物車的前置驗證與跳轉
  async function purchaseCourse(courseId, showToast) {
    const { useAuthStore } = await import('@/stores/auth.js')
    const authStore = useAuthStore()

    // 執行統一前置驗證，若不通過則直接中斷
    if (!(await validateMemberRequirements(authStore, showToast))) return

    try {
      const api = (await import('@/api/course/course.js')).default
      const checkoutRes = await api.post('/api/courses/checkout', null, {
        params: {
          courseId: courseId,
          quantity: 1,
          paymentMethod: 'ecpay'
        }
      })

      showToast('課程訂單已建立！')

      if (checkoutRes.data && checkoutRes.data.paymentHtml) {
        const div = document.createElement('div')
        div.innerHTML = checkoutRes.data.paymentHtml
        document.body.appendChild(div)
        const form = div.querySelector('form')
        if (form) {
          form.target = '_blank'
          form.submit()
          showToast('已開啟綠界安全支付新分頁，請在新分頁完成付款。', 'success')
          return
        }
      }
    } catch (err) {
      showToast(err.response?.data?.message || '購買失敗，請重試', 'error')
    }
  }

  return {
    categories,
    loading,
    loadCategories,
    privateCourses,
    groupCourses,
    currentPageNum,
    totalSize,
    searchQuery,
    courseTotalPages,
    courseTotalElements,
    displayedCourses,
    loadCourses,
    purchaseCourse,
  }
})