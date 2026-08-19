import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getTrainerCoursesPublic,
  getPrivateCoursesPublic,
  getCourseCategories
} from '@/api/course.js'

// [全域狀態庫] useCoursesStore (courses)
// 定義課程相關的 Pinia 狀態庫，提供課程方案與教練課程的資料管理功能。
export const useCoursesStore = defineStore('courses', () => {
  const coursesList = ref([])     // 課程商品方案列表（用於課程商城或方案介紹）
  const trainerCourses = ref([])  // 公開的教練排班課程列表（供前台預約時使用）
  const categories = ref([])      // 課程的分類清單
  const loading = ref(false)      // 課程 API 載入狀態

  // 載入課程分類
  async function loadCategories() {
    const res = await getCourseCategories()
    categories.value = (res.data || []).map(cat => ({
      ...cat,
      id: cat.courseCategoryId || cat.id
    }))
  }

  // 載入課程方案商品列表
  async function loadCourseProducts() {
    loading.value = true
    try {
      const res = await getPrivateCoursesPublic()
      coursesList.value = (res.data || []).map(c => ({
        ...c,
        id: c.privateCourseId || c.id,
        courseId: c.privateCourseId || c.id,
        productId: c.privateCourseId || c.id,
        courseType: 'PRIVATE' // 統一使用 courseType，移除舊的 type 欄位
      }))
    } finally {
      loading.value = false
    }
  }

  // 載入公開教練課程列表
  async function loadTrainerCourses() {
    const res = await getTrainerCoursesPublic()
    trainerCourses.value = (res.data || []).map(c => ({
      ...c,
      id: c.id || c.courseId,
      courseId: c.id || c.courseId,
      courseType: c.courseType === 'PRIVATE' ? 'PRIVATE' : 'GROUP' // 統一標準化為 courseType
    }))
  }

  return {
    coursesList,
    trainerCourses,
    categories,
    loading,
    loadCategories,
    loadCourseProducts,
    loadTrainerCourses
  }
})