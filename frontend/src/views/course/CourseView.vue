<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useCoursesStore } from '@/stores/courses.js'
import { useCartStore } from '@/stores/cart.js'
import { useAuthStore } from '@/stores/auth.js'
import { useRoute } from 'vue-router'
import { useFeedback } from '@/composables/useFeedback.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import CourseCard from '@/components/CourseCard.vue'
import CourseSearchBar from '@/components/CourseSearchBar.vue'

const coursesStore = useCoursesStore()
const cartStore = useCartStore()
const authStore = useAuthStore()
const route = useRoute()

const { 
  loading,
  displayedCourses: coursesList,
  courseTotalPages: totalPages,
  courseTotalElements: totalPrivateCoursesCount,
  currentPageNum
} = storeToRefs(coursesStore)

// 為了相容 HTML 範本原本讀取的 courses 變數名稱
const courses = computed(() => coursesList.value || [])
const displayedCoursesList = computed(() => coursesList.value || [])

const { feedback, showFeedback } = useFeedback()

// 分頁大小控制，串接至 Pinia 的 totalSize
const coursesListPageSize = ref(8)
watch(coursesListPageSize, (newVal) => {
  coursesStore.loadCourses({ page: 0, size: newVal })
})

// 計算當前分頁值，對應至 currentPageNum (由 0 開始)
const currentPage = computed({
  get: () => currentPageNum.value + 1,
  set: (val) => {
    currentPageNum.value = val - 1
  }
})

// 搜尋與篩選狀態
const searchParams = ref({ keyword: '', trainerId: '', categoryId: '' })
let searchDebounceTimer = null

async function fetchCourses() {
  coursesStore.searchQuery = searchParams.value.keyword || ''
  await coursesStore.loadCourses({
    keyword: searchParams.value.keyword,
    trainerId: searchParams.value.trainerId || null,
    categoryId: searchParams.value.categoryId || null,
    page: currentPageNum.value,
    size: coursesListPageSize.value
  })
}

// 搜尋條件變動時自動觸發
watch(searchParams, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    currentPageNum.value = 0
    fetchCourses()
  }, 400)
}, { deep: true })

// 監聽頁碼切換
watch(currentPageNum, () => {
  fetchCourses()
})

function onSearch(params) {
  searchParams.value = params
}

async function handleAddToCart(course) {
  if (!authStore.isLoggedIn) {
    showFeedback('請先登入會員以進行課程購買', 'error')
    return
  }
  const idVal = course.privateCourseId || course.id
  // 呼叫 coursesStore 的金流直接購買流程
  await coursesStore.purchaseCourse(idVal, (msg, type) => {
    showFeedback(msg, type === 'error' ? 'error' : 'success')
  })
}

// 體驗會籍狀態
const hasActiveSubscription = ref(false)

// 載入會籍狀態
async function checkMemberSubscription() {
  if (!authStore.isLoggedIn) {
    hasActiveSubscription.value = false
    return
  }
  try {
    const api = (await import('@/api/axios.instance.js')).default
    const res = await api.get('/api/me/subscription')
    if (res.data) {
      // 判斷是否為 ACTIVE 且未過期
      hasActiveSubscription.value = res.data.isActive === true || res.data.status === 'ACTIVE'
    }
  } catch (err) {
    // 404 或其他錯誤代表沒有訂閱紀錄
    hasActiveSubscription.value = false
  }
}

onMounted(async () => {
  await checkMemberSubscription()
  await coursesStore.loadCategories()
  await fetchCourses()
})

onUnmounted(() => {
  clearTimeout(searchDebounceTimer)
})
</script>

<template>
  <div class="page-container">
    <!-- 頁面標題 -->
    <div class="section-header">
      <span class="section-tag">COURSES</span>
      <h2 class="section-title">專業健身與教練課程</h2>
      <p class="section-desc">我們提供一對一私人教練客製化課程以及各類精緻小班課程，陪伴您安全、有效地達成健康與體態目標。</p>
    </div>

    <CourseSearchBar
      v-model="searchParams"
      :show-keyword="true"
      :show-trainer="true"
      :show-category="true"
      :show-type="false"
      @search="onSearch"
    />

    <!-- 上架的付費課程包 -->
    <div class="purchasable-section">
      <div class="purchasable-header-row">
        <div>
          <h3 class="sub-title">線上購買課程方案</h3>
          <p class="sub-desc">
            購買後，您將獲得可用課堂數額度，即可直接預約專屬教練。
          </p>
        </div>
        <!-- 控制列：方案顯示數量 -->
        <div class="list-control-header bg-none">
          <span class="total-count-text">共 {{ totalPrivateCoursesCount }} 個方案</span>
          <div class="page-size-selector">
            <label for="coursesListPageSize">顯示數量：</label>
            <select id="coursesListPageSize" v-model.number="coursesListPageSize" class="select-sm">
              <option :value="4">4 個</option>
              <option :value="8">8 個</option>
              <option :value="12">12 個</option>
              <option :value="16">16 個</option>
              <option :value="32">32 個</option>
            </select>
          </div>
        </div>
      </div>

      <div v-if="loading" class="spinner"></div>
      <div v-else-if="coursesList.length === 0" class="empty-state">
        <p>目前尚無上架的課程方案，請聯絡管理員。</p>
      </div>

      <div v-else>
        <div class="product-grid">
          <div
            v-for="p in displayedCoursesList"
            :key="p.id"
            :id="'product-card-' + p.id"
            :class="{ 'highlight-flash-card': Number(route.query.productId) === p.id }"
            class="product-card-wrap"
          >
            <CourseCard
              :course="p"
              :has-active-subscription="hasActiveSubscription"
              @purchase="handleAddToCart"
            />
          </div>
        </div>

        <!-- 分頁頁碼 -->
        <div class="pagination" v-if="totalPages > 1" style="margin-bottom: 4rem;">
          <button class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
          <button
            v-for="n in totalPages"
            :key="n"
            :class="['page-btn', { active: currentPage === n }]"
            @click="currentPage = n"
          >
            {{ n }}
          </button>
          <button class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
        </div>
      </div>
    </div>
    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.sub-title {
  font-size: 1.4rem;
  font-weight: 700;
  color: var(--primary-dark);
}
.sub-desc {
  margin-bottom: 1.5rem;
  color: var(--text-secondary);
  font-size: 0.9rem;
}
.product-card-wrap {
  width: 100%;
  transition: var(--transition);
}
.empty-state { text-align: center; padding: 4rem 0; color: var(--text-secondary); }

@keyframes border-flash {
  0%, 100% { box-shadow: 0 0 0 2px var(--primary); transform: scale(1.01); }
  50% { box-shadow: 0 0 0 8px rgba(188, 163, 143, 0.45); transform: scale(1.03); }
}
.highlight-flash-card {
  animation: border-flash 1.5s ease-in-out 3;
  border-radius: var(--radius-lg);
  display: inline-block;
  width: 100%;
}

.list-control-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  padding: 0.5rem 1rem;
  background: var(--bg-section, #f8fafc);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--border, #e2e8f0);
}

.list-control-header.bg-none {
  background: none;
  border: none;
  padding: 0;
  margin-bottom: 0;
}

.total-count-text {
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
  font-weight: 600;
}

.page-size-selector {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
}

.select-sm {
  padding: 0.3rem 1.5rem 0.3rem 0.6rem;
  font-size: 0.85rem;
  border-radius: 6px;
  border: 1px solid var(--border, #e2e8f0);
  background-color: var(--bg-card, #ffffff);
  color: var(--text-primary);
  cursor: pointer;
  outline: none;
  appearance: none;
  background-image: url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3e%3cpolyline points='6 9 12 15 18 9'%3e%3c/polyline%3e%3c/svg%3e");
  background-repeat: no-repeat;
  background-position: right 0.4rem center;
  background-size: 0.8rem;
}

.select-sm:focus {
  border-color: var(--primary);
}

.purchasable-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 1.5rem;
  border-bottom: 1px solid var(--border, #e2e8f0);
  padding-bottom: 0.75rem;
}

.purchasable-header-row .sub-desc {
  margin-bottom: 0;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 260px));
  gap: 1.5rem;
  justify-content: start;
}

/* 分頁按鈕樣式 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  margin-top: 2rem;
}
.page-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: var(--radius-sm, 6px);
  border: 1px solid var(--border, #e2e8f0);
  background-color: var(--bg-card, #ffffff);
  color: var(--text-primary);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
}
.page-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
  background-color: var(--bg-section);
}
.page-btn.active {
  background-color: var(--primary);
  border-color: var(--primary);
  color: #ffffff;
}
.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
