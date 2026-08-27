<template>
  <div>
    <!-- 課程方案 -->
    <section class="section bg-white" id="courses">
      <div class="page-container">
        <div class="section-header">
          <span class="section-tag">COURSES</span>
          <h2 class="section-title">多元課程方案</h2>
          <p class="section-desc">我們提供完整的課程體系與專業認證教練，陪伴您達成每一個階段的健身目標。</p>
        </div>
        <div v-if="coursesLoading" class="spinner"></div>
        <div v-else-if="displayedCourses.length === 0" class="empty-state">
          <p>目前尚無課程資料，請確認後端服務已啟動。</p>
        </div>
        <div class="courses-grid" v-else>
          <CourseCard
            v-for="c in displayedCourses"
            :key="c.id"
            :course="c"
            :has-active-subscription="hasActiveSubscription"
            @purchase="handleCoursePurchase"
          />
        </div>
        <div class="cta-center" style="text-align: center; margin-top: 3rem;">
          <RouterLink to="/courses" class="btn btn-outline">瀏覽所有課程方案 →</RouterLink>
        </div>
      </div>
    </section>

    <!-- 教練陣容 -->
    <section class="section bg-white">
      <div class="page-container">
        <div class="section-header">
          <span class="section-tag">TRAINERS</span>
          <h2 class="section-title">專業教練陣容</h2>
          <p class="section-desc">每位教練均擁有國際認證資格，擅長不同的專業領域，量身規劃最適合你的訓練計畫。</p>
        </div>
        <div v-if="trainersLoading" class="spinner"></div>
        <div v-else-if="dbTrainers.length === 0" class="empty-state">
          <p>目前尚無教練資料，請確認後端服務已啟動。</p>
        </div>
        <!-- 3. 有資料時：渲染教練卡片列表 -->
        <div class="trainers-grid" v-else>
          <div class="trainer-card" v-for="(t, idx) in dbTrainers" :key="t.trainerId || idx">
            <!-- 4. 有設定頭像就顯示圖片，否則顯示首字圓形占位符（搭配隨機背景色） -->
            <img v-if="t.profileImageUrl" :src="formatImageUrl(t.profileImageUrl)" class="trainer-avatar-lg" alt="Trainer Avatar" />
            <div v-else class="trainer-avatar-lg" :style="{ background: trainerColor(idx) }">
              {{ (t.displayName || '教')?.[0] }}
            </div>
            <!-- 5. 自動判斷並補上「教練」稱謂 -->
            <h3 class="trainer-name">{{ (t.displayName || '專業') + ((t.displayName || '').includes('教練') ? '' : ' 教練') }}</h3>
            <p class="trainer-specialty">{{ t.specialty || '專業教練' }}</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
// 引入 Vue 功能：用於定義 dbTrainers (ref)、篩選課程 (computed)、載入初始資料 (onMounted) 與顯示提示 (inject showToast)
import { ref, computed, onMounted, inject } from 'vue'
// 引入路由工具：RouterLink 用於跳轉頁面，useRouter 用於未登入跳轉至登入頁 (第 112 行)
import { RouterLink, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
// 引入 API 實例：api 用於取得教練清單 (第 183 行)
import api from '@/api/axios.instance.js'
// 引入會員狀態：用於確認登入 (第 110 行) 與檢查個人資料完整性 (第 117 行)
import { useAuthStore } from '@/stores/auth.js'
// 引入商品狀態：用於取得課程方案列表 (第 196 行)
import { useCoursesStore } from '@/stores/courses.js'
// 引入輔助工具：formatImageUrl 用於格式化教練與商品圖片路徑 (第 46 行)
import { formatImageUrl } from '@/utils/image.js'
// 引入子元件：ProductCard 用於顯示課程卡片 HTML (第 69-74 行)
import CourseCard from '@/components/CourseCard.vue'

const auth = useAuthStore()
const router = useRouter()
const showToast = inject('showToast')
const coursesStore = useCoursesStore()
// [自 Store 解構響應式屬性] privateCourses, groupCourses
const { privateCourses, groupCourses } = storeToRefs(coursesStore)
// [響應式狀態] coursesLoading (coursesloading)
// 為什麼要這麼做：是否正在載入課程資料的布林標記，預設為 true（進入頁面立即顯示轉圈），在請求完成後變更為 false。
const coursesLoading = ref(true)
// [響應式狀態] hasActiveSubscription (hasactivesubscription)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const hasActiveSubscription = ref(false)

// 篩選與混合課程：大部分是私教課，少部分團課
// [計算屬性] displayedCourses (displayedcourses)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const displayedCourses = computed(() => {
  const privates = Array.isArray(privateCourses.value) ? privateCourses.value : []
  const groups = Array.isArray(groupCourses.value) 
    ? groupCourses.value 
    : (groupCourses.value && Array.isArray(groupCourses.value.content) ? groupCourses.value.content : [])
  
  // 4 個私教課 + 4 個團課，各半均衡展示，補上 isPrivate 標記供 CourseCard 判斷
  const selectedPrivate = privates.slice(0, 4).map(c => ({ ...c, isPrivate: true }))
  const selectedGroup = groups.slice(0, 8 - selectedPrivate.length).map(c => ({ ...c, isPrivate: false }))
  
  return [...selectedPrivate, ...selectedGroup]
})

// [功能方法] handleCoursePurchase (處理課程購買點擊)
// 未登入 → 導登入頁；已登入 → 觸發購買結帳流程
async function handleCoursePurchase(course) {
  if (!auth.isLoggedIn) {
    router.push({ name: 'login', query: { redirect: '/courses' } })
    return
  }
  await coursesStore.purchaseCourse(course.id, showToast)
}

// [響應式狀態] dbTrainers (dbtrainers)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const dbTrainers = ref([])
// [響應式狀態] trainersLoading (trainersloading)
// 為什麼要這麼做：是否正在載入資料的布林標記，預設為 true（進入頁面立即顯示轉圈），在請求完成後變更為 false。
const trainersLoading = ref(true)

const COLORS = [
  'var(--primary)',
  'var(--accent)',
  'var(--primary-dark)',
  'var(--primary-light)',
]

// [功能方法] trainerColor (trainercolor)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
function trainerColor(index) {
  return COLORS[index % COLORS.length]
}

// [功能方法] loadDbTrainers (載入dbtrainers)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadDbTrainers() {
  trainersLoading.value = true
  try {
    const res = await api.get('/api/trainers')
    dbTrainers.value = res.data
  } catch (e) {
    console.error('Failed to load trainers', e)
  } finally {
    trainersLoading.value = false
  }
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展展現。
onMounted(async () => {
  try {
    coursesLoading.value = true
    await coursesStore.loadCourses({ page: 0, size: 8 })
    coursesLoading.value = false
    loadDbTrainers()

    if (auth.isLoggedIn) {
      try {
        const subRes = await api.get('/api/me/subscription', { skipGlobalError: true })
        hasActiveSubscription.value = subRes.data?.isActive || false
      } catch (e) {
        // 忽略過期 Token 產生的錯誤，避免首頁強制跳轉或警告
      }
    }
  } catch (e) {
    coursesLoading.value = false
    console.warn('HomeCourses mounted error:', e?.message || e)
  }
})
</script>

<style src="../home.css"></style>
