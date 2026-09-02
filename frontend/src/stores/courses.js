import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getPrivateCoursesPublic,
  getGroupCoursesPublic,
  getCourseCategories,
} from '@/api/course/course.js'
import api from '@/api/axios.instance.js'
import { redirectToEcpay } from '@/composables/useEcpayCheckout.js'

// [全域狀態庫] useCoursesStore (courses)
// 定義課程相關的 Pinia 狀態庫，提供課程方案與教練課程的資料管理功能。
export const useCoursesStore = defineStore('courses', () => {
  const categories = ref([]) // 課程商品方案列表（用於課程商城或方案介紹）
  const privateCourses = ref([]) // 私教課方案列表（用於課程商城或方案介紹）
  const groupCourses = ref([]) // 團課方案列表（用於課程商城或方案介紹）
  const loading = ref(false) // 課程 API 載入狀態

  // 響應式分頁狀態 (前端分頁使用)
  const currentPageNum = ref(0)
  const totalSize = ref(8)
  const searchQuery = ref('')

  //載入課程分類
  async function loadCategories() {
    const res = await getCourseCategories()
    categories.value = (res.data || []).map((c) => ({
      ...c,
      id: c.courseCategoryId || c.id,
    }))
  }

  const allCourses = computed(() => {
    const privates = Array.isArray(privateCourses.value) ? privateCourses.value : []
    const groups = Array.isArray(groupCourses.value)
      ? groupCourses.value
      : groupCourses.value?.content && Array.isArray(groupCourses.value.content)
        ? groupCourses.value.content
        : []
    return [
      ...privates.map((c) => ({
        ...c,
        id: c.id || c.privateCourseId || c.courseId,
        isPrivate: true,
      })),
      ...groups.map((c) => ({ ...c, id: c.id || c.courseId || c.groupCourseId, isPrivate: false })),
    ]
  })

  // 搜尋與篩選的狀態欄位 (響應式)
  const selectedCategoryId = ref(null)
  const selectedTrainerId = ref(null)

  // 計算屬性：搜尋篩選（私教課 + 團課）
  const filteredCourses = computed(() => {
    let result = allCourses.value

    // 1. 關鍵字過濾
    const keyword = searchQuery.value.trim().toLowerCase()
    if (keyword) {
      result = result.filter((course) => {
        const nameMatch = course.name?.toLowerCase().includes(keyword)
        const trainerName =
          course.trainerName || course.trainer?.name || course.trainer?.displayName || ''
        const trainerMatch = trainerName.toLowerCase().includes(keyword)
        return nameMatch || trainerMatch
      })
    }

    // 2. 分類過濾
    if (selectedCategoryId.value) {
      const catId = Number(selectedCategoryId.value)
      result = result.filter((course) => {
        const cId =
          course.courseCategoryId ||
          course.category?.courseCategoryId ||
          course.category?.id ||
          course.categoryId
        return Number(cId) === catId
      })
    }

    // 3. 教練過濾
    if (selectedTrainerId.value) {
      const tId = Number(selectedTrainerId.value)
      result = result.filter((course) => {
        // 團課是扁平的 trainerId；私教課是巢狀 trainer 物件（TrainerDto 的主鍵叫 id）
        const trId = course.trainerId || course.trainer?.trainerId || course.trainer?.id
        return Number(trId) === tId
      })
    }

    return result
  })

  // 計算屬性：總筆數與總頁數 (自動隨 filteredCourses 計算)
  const courseTotalElements = computed(() => filteredCourses.value.length)
  const courseTotalPages = computed(
    () => Math.ceil(filteredCourses.value.length / totalSize.value) || 1,
  )

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

    // 同步外部傳入的篩選條件到 Pinia 響應式屬性
    selectedCategoryId.value = params.categoryId || null
    selectedTrainerId.value = params.trainerId || null
    searchQuery.value = params.keyword || ''

    loading.value = true
    try {
      const [privateRes, groupRes] = await Promise.all([
        getPrivateCoursesPublic(),
        getGroupCoursesPublic(),
      ])

      const rawGroup = groupRes.data
      privateCourses.value = Array.isArray(privateRes.data)
        ? privateRes.data
        : privateRes.data?.content || []
      // 團課走後端共用的 PageResponse，分頁清單放在 items（不是 Spring Page 的 content）；
      // content 保留當備援，避免之後有端點改回 Page 直接序列化
      groupCourses.value = Array.isArray(rawGroup)
        ? rawGroup
        : Array.isArray(rawGroup?.items)
          ? rawGroup.items
          : Array.isArray(rawGroup?.content)
            ? rawGroup.content
            : []
    } catch (e) {
      // 公開課程 API 若失敗（如後端尚未啟動），靜默處理，不讓錯誤往上拋造成全域 session expired 流程
      console.warn('loadCourses failed:', e?.message || e)
    } finally {
      loading.value = false
    }
  }

  // 統一前置驗證：依序檢查登入狀態與會員個人資料完整度
  async function validateMemberRequirements(authStore, showToast) {
    // 1. 檢查是否登入
    if (!authStore.isLoggedIn) {
      showToast('請先登入會員以進行課程購買', 'info')
      window.location.href = '/login?redirect=/courses'
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
      // 1. 建立課程直接購買訂單
      const orderRes = await api.post('/api/orders/direct', {
        privateCourseId: courseId,
        quantity: 1,
      })

      const orderData = orderRes.data
      if (!orderData || !orderData.orderId) {
        showToast('建立訂單失敗，回傳資料無效', 'error')
        return
      }

      showToast('課程訂單已建立，前往付款…', 'success')

      // 2. 取綠界表單 → 記下待確認訂單 → 全屏過場 → 送出導向綠界（與購物車／訂閱結帳共用 useEcpayCheckout）
      await redirectToEcpay(orderData.orderId, { method: 'Credit' })
    } catch (err) {
      showToast(err.message || err.response?.data?.message || '購買失敗，請重試', 'error')
    }
  }

  return {
    categories,
    loading,
    loadCategories,
    privateCourses,
    groupCourses,
    allCourses,
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
