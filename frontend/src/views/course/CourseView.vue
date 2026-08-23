<script setup>
// Vue 核心響應式 API：定義狀態、生命週期
import { ref, watch, onMounted, computed } from 'vue'
// 路由控制 API：跳轉頁面 (RouterLink)
import { RouterLink, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCoursesStore } from '@/stores/courses.js'
import { notifyApiError } from '@/api/error-bus.js'
import Pagination from '@/components/Pagination.vue'

const route = useRoute()
const coursesStore = useCoursesStore()

// 從 Pinia Store 中解構出團體課程列表與載入狀態
const { 
  loading, 
  displayedCourses, 
  currentPageNum: page, 
  totalSize: size, 
  courseTotalPages: totalPages,
  searchQuery: search 
} = storeToRefs(coursesStore)

const loadError = ref(false)

function handlePageChange(newPage) {
  page.value = newPage
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
}

// 向後端請求獲取原始課程資料
async function fetchCourses() {
  loadError.value = false
  try {
    await coursesStore.loadCourses()
  } catch (err) {
    loadError.value = true
    notifyApiError(err)
  }
}

// 監聽搜尋條件改變時，自動將頁碼歸零
watch(search, () => {
  page.value = 0
})

// 元件掛載初始化
onMounted(async () => {
  // 首頁搜尋跳轉相容
  if (route.query.search) {
    search.value = route.query.search
  }

  // 載入全部課程資料
  await fetchCourses()

  // 網址攜帶 courseId 時的平滑捲動定位
  if (route.query.courseId) {
    const targetId = 'course-card-' + route.query.courseId
    let attempts = 0
    const interval = setInterval(() => {
      const el = document.getElementById(targetId)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        clearInterval(interval)
      }
      attempts++
      if (attempts > 30) clearInterval(interval)
    }, 100)
  }
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">COURSES</span>
    <h2 class="section-title">團體課程介紹</h2>
    <p class="section-desc">瀏覽目前開放中的團體課程，選定喜歡的課程後即可前往查看場次並報名。</p>

    <div class="search-field">
      <input id="courseSearch" v-model="search" type="text" placeholder="搜尋課程名稱或教練姓名" />
    </div>

    <div v-if="loading" class="spinner"></div>

    <template v-else-if="loadError">
      <p class="empty-state">課程資料載入失敗，請稍後再試。</p>
      <button class="btn btn-outline" type="button" @click="fetchCourses">重新整理</button>
    </template>

    <template v-else>
      <p v-if="displayedCourses.length === 0" class="empty-state">目前沒有開放中的團課</p>

      <div v-else class="course-grid">
        <div v-for="course in displayedCourses" :key="course.id" :id="'course-card-' + course.id" class="course-card">
          <div class="course-card-header">
            <h3 class="course-name">{{ course.name }}</h3>
            <span v-if="course.isTrial" class="status-badge status-trial">體驗課</span>
          </div>
          <p class="course-trainer">教練：{{ course.trainer?.name || '暫無教練' }}</p>
          <p class="course-description">{{ course.description || '暫無課程簡介' }}</p>
          <p class="course-capacity">最多 {{ course.maxCapacity }} 人</p>
          <RouterLink class="btn btn-accent course-link" to="/group-classes">查看時段並報名</RouterLink>
        </div>
      </div>

      <Pagination
        :page="page"
        :total-pages="totalPages"
        :size="size"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      />
    </template>
  </div>
</template>

<style scoped>
.course-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 1.25rem;
}

.course-card {
  display: flex;
  flex-direction: column;
  padding: 1.5rem;
  background: var(--bg-section);
  border: 1.5px solid var(--border);
  border-radius: var(--radius-md);
}

.course-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
}

.course-name {
  margin: 0;
  font-size: 1.05rem;
  color: var(--text-primary);
}

.course-trainer {
  margin: 0 0 0.5rem;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--accent-hover);
}

.course-description {
  margin: 0 0 0.75rem;
  font-size: 0.9rem;
  color: var(--text-secondary);
  flex: 1;
}

.course-capacity {
  margin: 0 0 1.25rem;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.course-link {
  align-self: flex-start;
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
  white-space: nowrap;
}

.status-trial {
  background: #e3ecf5;
  color: #38618c;
}

.empty-state {
  color: var(--text-muted);
  padding: 2rem 0;
}

.search-field {
  margin-bottom: 1rem;
}

.search-field input {
  width: 100%;
  max-width: 320px;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
}
</style>
