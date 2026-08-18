<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { RouterLink } from 'vue-router'
import { getActiveCourses } from '@/api/groupClass.js'
import { notifyApiError } from '@/api/error-bus.js'
import Pagination from '@/components/Pagination.vue'

const courses = ref([])
const loading = ref(true)
// 純瀏覽頁，載入失敗沒有「就地顯示在按鈕旁」的情境，改用全域錯誤條 + 頁面內簡短狀態雙軌呈現，
// 讓使用者知道「不是沒有資料，是讀取失敗」，並提供重新整理的入口
const loadError = ref(false)

// 分頁與搜尋狀態
const page = ref(0)
const size = ref(10)
const search = ref('')
const totalItems = ref(0)
const totalPages = ref(0)
let searchDebounceTimer = null

async function fetchCourses() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getActiveCourses({ page: page.value, size: size.value, search: search.value })
    courses.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } catch (err) {
    loadError.value = true
    notifyApiError(err)
  } finally {
    loading.value = false
  }
}

// 搜尋字串變動時 debounce 400ms 才觸發查詢；換了搜尋條件代表結果集變了，
// page 一律重設為 0，避免卡在一個搜尋後根本不存在的頁碼（寫法比照 AdminGroupCourseView.vue）
watch(search, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchCourses()
  }, 400)
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchCourses()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchCourses()
}

onMounted(() => {
  fetchCourses()
})

onUnmounted(() => {
  clearTimeout(searchDebounceTimer)
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
      <p v-if="courses.length === 0" class="empty-state">目前沒有開放中的團課</p>

      <div v-else class="course-grid">
        <div v-for="course in courses" :key="course.id" class="course-card">
          <div class="course-card-header">
            <h3 class="course-name">{{ course.name }}</h3>
            <span v-if="course.isTrial" class="status-badge status-trial">體驗課</span>
          </div>
          <p class="course-trainer">教練：{{ course.trainerName }}</p>
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
