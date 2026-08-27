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
import AppPagination from '@/components/AppPagination.vue'
import { getGroupCoursesPublic, getPrivateCoursesPublicPaged } from '@/api/course/course.js'

const coursesStore = useCoursesStore()
const cartStore = useCartStore()
const authStore = useAuthStore()
const route = useRoute()

const { 
  loading,
  displayedCourses: coursesList,
  courseTotalElements: totalPrivateCoursesCount
} = storeToRefs(coursesStore)

// 為了相容 HTML 範本原本讀取的 courses 變數名稱
const courses = computed(() => coursesList.value || [])
const displayedCoursesList = computed(() => coursesList.value || [])

const { feedback, showFeedback } = useFeedback()

// 私教課後端分頁狀態與資料庫
const privatePageNum = ref(1) // AppPagination is 1-indexed
const privatePageSize = ref(6)
const privateCoursesData = ref({
  items: [],
  totalElements: 0,
  totalPages: 1
})

// 團體課後端分頁狀態與資料庫
const groupPageNum = ref(1) // AppPagination is 1-indexed
const groupPageSize = ref(8)
const groupCoursesData = ref({
  items: [],
  totalElements: 0,
  totalPages: 1
})

// 監聽分頁大小變化，自動重設頁碼
watch(privatePageSize, () => { privatePageNum.value = 1 })
watch(groupPageSize, () => { groupPageNum.value = 1 })

// 監聽私教課分頁頁碼與大小變化，動態重載後端資料
watch([privatePageNum, privatePageSize], () => {
  fetchCourses()
})

// 搜尋與篩選狀態
const searchParams = ref({ keyword: '', trainerId: '', categoryId: '' })
let searchDebounceTimer = null

async function fetchCourses() {
  try {
    const res = await getPrivateCoursesPublicPaged({
      page: privatePageNum.value - 1, // 後端 API 為 0-indexed
      size: privatePageSize.value,
      categoryId: searchParams.value.categoryId || null,
      trainerId: searchParams.value.trainerId || null,
      search: searchParams.value.keyword || ''
    })
    const raw = res.data
    if (raw) {
      privateCoursesData.value = {
        items: raw.items || raw.content || [],
        totalElements: raw.totalItems ?? raw.totalElements ?? 0,
        totalPages: raw.totalPages || 1
      }
    }
  } catch (e) {
    console.error('fetchCourses failed:', e)
  }
}

// 專門向後端請求分頁團體課程的函數
async function fetchGroupCourses() {
  try {
    const res = await getGroupCoursesPublic({
      page: groupPageNum.value - 1, // 後端 API 為 0-indexed
      size: groupPageSize.value,
      categoryId: searchParams.value.categoryId || null,
      trainerId: searchParams.value.trainerId || null,
      search: searchParams.value.keyword || ''
    })
    const raw = res.data
    if (raw) {
      groupCoursesData.value = {
        items: raw.items || raw.content || [],
        totalElements: raw.totalItems ?? raw.totalElements ?? 0,
        totalPages: raw.totalPages || 1
      }
    }
  } catch (e) {
    console.error('fetchGroupCourses failed:', e)
  }
}

// 搜尋條件變動時自動觸發
watch(searchParams, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    privatePageNum.value = 1
    groupPageNum.value = 1
    fetchCourses()
    fetchGroupCourses()
  }, 400)
}, { deep: true })

// 監聽團課分頁頁碼與大小變化，動態重載後端資料
watch([groupPageNum, groupPageSize], () => {
  fetchGroupCourses()
})

function onSearch(params) {
  searchParams.value = params
}

// 頁面選單分類 (私教課 / 團課)
const activeTab = ref('private')

// 監聽頁籤切換，自動重設篩選條件與頁碼，避免不同頁籤的種類衝突導致空白畫面
watch(activeTab, () => {
  searchParams.value = { keyword: '', trainerId: '', categoryId: '' }
  privatePageNum.value = 1
  groupPageNum.value = 1
  fetchCourses()
  fetchGroupCourses()
})

// 格式化價格
const formatPrice = (price) => Number(price).toLocaleString('zh-TW')

// 根據當前 activeTab 來過濾搜尋下拉選單中顯示的教練名單
const displayedDropdownTrainers = computed(() => {
  if (activeTab.value === 'private') {
    const trainersMap = new Map()
    const list = coursesStore.privateCourses || []
    list.forEach(c => {
      const t = c.trainer
      if (t && !trainersMap.has(t.id)) {
        trainersMap.set(t.id, t)
      }
    })
    return Array.from(trainersMap.values())
  }
  return null // 團課則顯示後端所有教練（這 12 位教練皆有分配到團課）
})

// 篩選出團體課程清單 (後端已處理分頁與過濾)
const filteredGroupCourses = computed(() => {
  return groupCoursesData.value.items || []
})

// 教練字卡分組 (依據當前分頁私教課的教練分組)
const trainersWithPrivateCourses = computed(() => {
  const trainersMap = {}
  const list = privateCoursesData.value.items || []
  list.forEach(course => {
    const trainer = course.trainer
    if (!trainer) return
    if (!trainersMap[trainer.id]) {
      trainersMap[trainer.id] = {
        id: trainer.id,
        name: trainer.name,
        specialty: trainer.specialty,
        bio: trainer.bio,
        experience: trainer.experience,
        avatarUrl: trainer.avatarUrl,
        courses: []
      }
    }
    trainersMap[trainer.id].courses.push(course)
  })
  return Object.values(trainersMap)
})

const privateTotalPages = computed(() => {
  return privateCoursesData.value.totalPages || 1
})

const displayedTrainers = computed(() => {
  return trainersWithPrivateCourses.value
})

// 各教練選取的課程方案對應 ID Map
const selectedCoursesMap = ref({})

// 監聽並自動為每位教練預設第一個課程方案
watch(trainersWithPrivateCourses, (newVal) => {
  newVal.forEach(t => {
    if (!selectedCoursesMap.value[t.id] && t.courses.length > 0) {
      selectedCoursesMap.value[t.id] = t.courses[0].id
    }
  })
}, { immediate: true })

async function handleAddToCart(course) {
  if (!authStore.isLoggedIn) {
    showFeedback('請先登入會員以進行課程購買', 'error')
    await coursesStore.purchaseCourse(course.id || course.privateCourseId, () => {})
    return
  }
  const idVal = course.privateCourseId || course.id
  await coursesStore.purchaseCourse(idVal, (msg, type) => {
    showFeedback(msg, type === 'error' ? 'error' : 'success')
  })
}

// 體驗會籍狀態
const hasActiveSubscription = ref(false)

const displayedCategories = computed(() => {
  const allCats = coursesStore.categories || []
  if (activeTab.value === 'private') {
    return allCats.filter(c => c.name === '重量訓練')
  }
  return allCats
})

// 載入會籍狀態
async function checkMemberSubscription() {
  if (!authStore.isLoggedIn || !authStore.isMember) {
    hasActiveSubscription.value = false
    return
  }
  try {
    const api = (await import('@/api/axios.instance.js')).default
    const res = await api.get('/api/me/subscription')
    if (res.data) {
      hasActiveSubscription.value = res.data.isActive === true || res.data.status === 'ACTIVE'
    }
  } catch (err) {
    hasActiveSubscription.value = false
  }
}

onMounted(async () => {
  await checkMemberSubscription()
  await coursesStore.loadCategories()
  await fetchCourses()
  await fetchGroupCourses()
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

    <!-- 頁面選單分類切換 (一對一私教 / 團課) -->
    <div class="tabs-container">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'private' }"
        @click="activeTab = 'private'"
      >
        私人教練課程
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'group' }"
        @click="activeTab = 'group'"
      >
        團體課程
      </button>
    </div>

    <CourseSearchBar
      v-model="searchParams"
      :show-keyword="true"
      :show-trainer="true"
      :show-category="true"
      :show-type="false"
      :categories="displayedCategories"
      :trainers="displayedDropdownTrainers"
      @search="onSearch"
    />

    <!-- 上架的付費課程包 -->
    <div class="purchasable-section">
      <div class="purchasable-header-row">
        <div>
          <h3 class="sub-title">
            {{ activeTab === 'private' ? '挑選您的專屬私人教練' : '線上預約團體課程' }}
          </h3>
          <p class="sub-desc">
            {{ activeTab === 'private' ? '選擇心儀的教練，並挑選其提供的客製化課程方案直接進行選購。' : '訂閱體驗會員即可享有無限制團體課程免費預約額度。' }}
          </p>
        </div>
      </div>

      <div v-if="loading" class="spinner"></div>

      <!-- 私教課區塊：以教練字卡呈現 -->
      <div v-else-if="activeTab === 'private'">
        <div v-if="trainersWithPrivateCourses.length === 0" class="empty-state">
          <p>目前尚無符合篩選條件的教練私教課程方案。</p>
        </div>

        <div v-else>
          <!-- 上方分頁按鈕 (同後台雙分頁樣式) -->
          <AppPagination
            v-model="privatePageNum"
            :total-pages="privateTotalPages"
            :total-elements="privateCoursesData.totalElements"
            :page-size="privatePageSize"
            :show-size-selector="true"
            :size-options="[3, 6, 9, 12, 18]"
            @update:pageSize="privatePageSize = $event"
            style="margin-bottom: 1.5rem;"
          />

          <div class="coach-grid">
            <div
              v-for="trainer in displayedTrainers"
              :key="trainer.id"
              class="coach-card"
            >
              <!-- 上半部：教練個人資訊 -->
              <div class="coach-header">
                <div class="coach-avatar-wrapper">
                  <img
                    v-if="trainer.avatarUrl"
                    :src="trainer.avatarUrl"
                    class="coach-avatar"
                    alt="教練頭像"
                  />
                  <div v-else class="coach-avatar-placeholder">
                    {{ trainer.name ? trainer.name.charAt(0) : '教' }}
                  </div>
                </div>
                <div class="coach-info">
                  <h4 class="coach-name">
                    {{ trainer.name }}
                    <span class="coach-title-tag">專業教練</span>
                  </h4>
                  <div class="coach-specialty-badges">
                    <span
                      v-for="(spec, idx) in (trainer.specialty ? trainer.specialty.split('、') : [])"
                      :key="idx"
                      class="spec-badge"
                    >
                      {{ spec }}
                    </span>
                  </div>
                  <p class="coach-exp"><strong>經歷：</strong>{{ trainer.experience || '多年專業教學經驗' }}</p>
                </div>
              </div>

              <!-- 中部：教練格言/自傳 -->
              <div class="coach-bio" v-if="trainer.bio">
                <span class="quote-mark">“</span>
                {{ trainer.bio }}
                <span class="quote-mark">”</span>
              </div>

              <!-- 下半部：方案選擇與購買區 -->
              <div class="coach-course-selector-box">
                <label :for="'course-select-' + trainer.id" class="form-label">選擇私教課程方案：</label>
                <select
                  :id="'course-select-' + trainer.id"
                  v-model="selectedCoursesMap[trainer.id]"
                  class="form-input"
                >
                  <option
                    v-for="course in trainer.courses"
                    :key="course.id"
                    :value="course.id"
                  >
                    {{ course.name }} - NT$ {{ formatPrice(course.price) }}
                  </option>
                </select>

                <!-- 選取的課程詳情 (使用共用卡片與設計語彙) -->
                <div
                  class="selected-course-details"
                  v-if="trainer.courses.find(c => c.id === selectedCoursesMap[trainer.id])"
                  style="border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 0.75rem; background: var(--bg-section);"
                >
                  <div class="item-footer" style="margin-top: 0; margin-bottom: 0.5rem; justify-content: flex-start; align-items: center; gap: 0.75rem;">
                    <span class="price">
                      NT$ {{ formatPrice(trainer.courses.find(c => c.id === selectedCoursesMap[trainer.id]).price) }}
                    </span>
                    <span class="spec-badge" style="font-size: 0.7rem; font-weight: 600; padding: 0.15rem 0.45rem;">
                      共 {{ trainer.courses.find(c => c.id === selectedCoursesMap[trainer.id]).sessionPerUnit }} 堂課
                    </span>
                  </div>
                  <p class="item-desc" style="min-height: auto; height: auto; -webkit-line-clamp: unset; line-clamp: unset; margin-bottom: 0.75rem; line-height: 1.55;">
                    {{ trainer.courses.find(c => c.id === selectedCoursesMap[trainer.id]).description || '精緻一對一專屬私人課程，客製化個人體態與訓練計畫。' }}
                  </p>

                  <button
                    class="btn btn-accent"
                    style="width: 100%; border-radius: 999px;"
                    @click="handleAddToCart(trainer.courses.find(c => c.id === selectedCoursesMap[trainer.id]))"
                  >
                    確認購買課程
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- 下方分頁按鈕 -->
          <AppPagination
            v-model="privatePageNum"
            :total-pages="privateTotalPages"
            :total-elements="privateCoursesData.totalElements"
            :page-size="privatePageSize"
            :show-size-selector="true"
            :size-options="[3, 6, 9, 12, 18]"
            @update:pageSize="privatePageSize = $event"
            style="margin-top: 2.5rem; margin-bottom: 4rem;"
          />
        </div>
      </div>

      <!-- 團體課區塊：傳統卡片清單 -->
      <div v-else-if="activeTab === 'group'">
        <div v-if="filteredGroupCourses.length === 0" class="empty-state">
          <p>目前尚無符合篩選條件的團體課程方案。</p>
        </div>

        <div v-else>
          <!-- 上方分頁按鈕 (同後台雙分頁樣式) -->
          <AppPagination
            v-model="groupPageNum"
            :total-pages="groupCoursesData.totalPages"
            :total-elements="groupCoursesData.totalElements"
            :page-size="groupPageSize"
            :show-size-selector="true"
            :size-options="[4, 8, 12, 16, 32]"
            @update:pageSize="groupPageSize = $event"
            style="margin-bottom: 1.5rem;"
          />

          <div class="product-grid">
            <div
              v-for="p in filteredGroupCourses"
              :key="p.id"
              class="product-card-wrap"
            >
              <CourseCard
                :course="p"
                :has-active-subscription="hasActiveSubscription"
                @purchase="handleAddToCart"
              />
            </div>
          </div>

          <!-- 下方分頁按鈕 -->
          <AppPagination
            v-model="groupPageNum"
            :total-pages="groupCoursesData.totalPages"
            :total-elements="groupCoursesData.totalElements"
            :page-size="groupPageSize"
            :show-size-selector="true"
            :size-options="[4, 8, 12, 16, 32]"
            @update:pageSize="groupPageSize = $event"
            style="margin-top: 2.5rem; margin-bottom: 4rem;"
          />
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

/* Tabs 樣式 */
.tabs-container {
  display: flex;
  gap: 1.5rem;
  border-bottom: 1.5px solid var(--border);
  margin-bottom: 2rem;
}
.tab-btn {
  background: none;
  border: none;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-secondary, #64748b);
  padding: 0.75rem 1.5rem;
  cursor: pointer;
  border-bottom: 3px solid transparent;
  transition: all 0.25s ease;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.tab-btn:hover {
  color: var(--primary);
}
.tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

/* 教練字卡網格與卡片 */
.coach-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 2rem;
  justify-content: start;
}
.coach-card {
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border, #e2e8f0);
  border-radius: var(--radius-lg, 12px);
  padding: 1.5rem;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}
.coach-card:hover {
  transform: translateY(-5px);
  box-shadow: var(--shadow-hover);
  border-color: var(--primary);
}

/* 教練資訊區部 */
.coach-header {
  display: flex;
  gap: 1.25rem;
  align-items: flex-start;
  margin-bottom: 1rem;
}
.coach-avatar-wrapper {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  border: 3px solid var(--primary-light, #fef3c7);
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
}
.coach-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.coach-avatar-placeholder {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--primary-light, #fde68a), var(--primary, #bca38f));
  color: #fff;
  font-size: 2rem;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  text-shadow: 1px 1px 2px rgba(0,0,0,0.1);
}
.coach-info {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.coach-name {
  font-size: 1.2rem;
  font-weight: 800;
  color: var(--text-primary);
  margin: 0;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.coach-title-tag {
  font-size: 0.7rem;
  background-color: var(--primary-light, #fef3c7);
  color: #92400e;
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  font-weight: 700;
}
.coach-specialty-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}
.spec-badge {
  font-size: 0.72rem;
  background-color: var(--bg-section, #f8fafc);
  color: var(--text-secondary, #64748b);
  border: 1px solid var(--border, #e2e8f0);
  padding: 0.15rem 0.5rem;
  border-radius: 12px;
  font-weight: 600;
}
.coach-exp {
  font-size: 0.8rem;
  color: var(--text-secondary);
  margin: 0;
  line-height: 1.4;
}

/* 教練自傳/格言 */
.coach-bio {
  font-size: 0.85rem;
  font-style: italic;
  color: var(--text-secondary, #64748b);
  background-color: var(--bg-section, #f8fafc);
  border-left: 3px solid var(--primary);
  padding: 0.6rem 1rem;
  margin: 0 0 1.25rem 0;
  border-radius: 0 var(--radius-sm, 4px) var(--radius-sm, 4px) 0;
  line-height: 1.5;
  display: flex;
  align-items: flex-start;
}
.quote-mark {
  font-size: 1.4rem;
  font-weight: 800;
  color: var(--primary);
  line-height: 1;
  margin: 0 0.2rem;
}

/* 課程方案選擇盒 */
.coach-course-selector-box {
  border-top: 1px dashed var(--border, #e2e8f0);
  padding-top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

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
</style>
