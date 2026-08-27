<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { getMyQuotas, getMyReservations } from '@/api/member/booking.js'
import { useCoursesStore } from '@/stores/courses.js'
import { isCoursePrivate } from '@/utils/courseType.js'

import CourseTypeBadge from '@/components/CourseTypeBadge.vue'
import BaseSelect from '@/components/BaseSelect.vue'

import CourseSearchBar from '@/components/CourseSearchBar.vue'

const router = useRouter()
const showToast = inject('showToast')

const searchParams = ref({ keyword: '', trainerId: '', expiryBefore: '' })

watch(searchParams, (newVal) => {
  searchQuery.value = newVal.keyword || ''
  selectedTrainerId.value = newVal.trainerId || ''
  expiryBefore.value = newVal.expiryBefore || ''
}, { deep: true })

// [響應式狀態] activeTab (activetab)
// 為什麼要這麼做：啟用狀態。
const activeTab = ref('quotas')
// [響應式狀態] quotas (quotas)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const quotas = ref([])
const coursesStore = useCoursesStore()
const { allCourses } = storeToRefs(coursesStore)
// [響應式狀態] myReservations (myreservations)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const myReservations = ref([])
// [響應式狀態] loading (loading)
// 為什麼要這麼做：是否正在載入資料的布林標記，用以在請求完成前控制 Spinner 載入動畫的顯示。
const loading = ref(true)
// [響應式狀態] expandedHistories (expandedhistories)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const expandedHistories = ref(new Set())

// [響應式狀態] searchQuery (搜尋查詢)
// 為什麼要這麼做：根據使用者輸入的關鍵字或篩選條件，重新過濾出符合條件的清單，提供快速檢索功能。
const searchQuery = ref('')
// [響應式狀態] selectedTrainerId (selectedtrainerid)
// 為什麼要這麼做：在列表中標記並記錄使用者目前點選的項目，供下一步的行為（如預約、結帳）做為對象。
const selectedTrainerId = ref('')
// [響應式狀態] selectedType (selectedtype)
const selectedType = ref('')
// [響應式狀態] expiryBefore (效期截止前日期篩選)
// 填入後只顯示效期早於此日期的額度（方便找出快過期的方案）
const expiryBefore = ref('')

// Key generation state
// [響應式狀態] generatingKeyId (generatingkeyid)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const generatingKeyId = ref(null)

// [功能方法] formatDate (格式化date)
// 為什麼要這麼做：將原始的資料（如日期、數值）轉換為符合本地語系與高可讀性的格式（如千分位、kg 單位）。
function formatDate(dateStr) {
  if (!dateStr) return '無期限'
  const d = new Date(dateStr)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

// [功能方法] formatReservationTimeRange
// 從 startAt／endAt 組出「日期 HH:mm-HH:mm」的顯示格式
function formatReservationTimeRange(r) {
  if (!r.startAt || !r.endAt) return ''
  const start = new Date(r.startAt)
  const end = new Date(r.endAt)
  const pad = (n) => String(n).padStart(2, '0')
  const timeStr = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${formatDate(r.startAt)} ${timeStr(start)}-${timeStr(end)}`
}

// [功能方法] isNearExpiry (isnearexpiry)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
function isNearExpiry(expiryDate) {
  if (!expiryDate) return false
  const diffDays = Math.ceil((new Date(expiryDate) - new Date()) / (1000 * 60 * 60 * 24))
  return diffDays <= 7 && diffDays >= 0
}

// [功能方法] getPercentage (取得percentage)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getPercentage(value, quota) {
  const total = quota.remainingCourse + quota.reservedCourse + quota.usedCourse
  return total === 0 ? 0 : (value / total) * 100
}

// [功能方法] getQuotaCourse (取得quotacourse)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getQuotaCourse(quota) {
  return allCourses.value.find(c => {
    if (quota.productId && c.productId === quota.productId) return true
    if (c.name && quota.courseName) {
      return c.name.includes(quota.courseName) || quota.courseName.includes(c.name)
    }
    return false
  })
}

// [功能方法] getQuotaTrainerName (取得quotatrainername)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getQuotaTrainerName(quota) {
  const m = getQuotaCourse(quota)
  return m?.trainer ? `${m.trainer.lastName || ''}${m.trainer.firstName || ''}` : ''
}

// [功能方法] getQuotaTypeLabel (取得quotatypelabel)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getQuotaTypeLabel(quota) {
  const m = getQuotaCourse(quota)
  return m ? (isCoursePrivate(m) ? '私教課' : '團課') : ''
}

// [功能方法] getUsageRecords (取得usagerecords)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getUsageRecords(quota) {
  return myReservations.value
    .filter(r => {
      if (r.status === 'CANCELLED' || !r.courseName) return false
      const qName = (quota.courseName || '').trim().toLowerCase()
      const rName = r.courseName.trim().toLowerCase()
      return rName.includes(qName) || qName.includes(rName)
    })
    .sort((a, b) => new Date(b.startAt) - new Date(a.startAt))
}

// [功能方法] toggleHistory (切換history)
// 為什麼要這麼做：切換布林值狀態（例如展開/收合、明文/密碼），以最直覺的方式改變介面的視覺呈現。
function toggleHistory(quotaId) {
  const s = new Set(expandedHistories.value)
  s.has(quotaId) ? s.delete(quotaId) : s.add(quotaId)
  expandedHistories.value = s
}

// [功能方法] handleBookCourse (處理bookcourse)
// 為什麼要這麼做：響應使用者的點擊、輸入或提交等操作行為，執行相對應的業務流程邏輯。
function handleBookCourse(quota) {
  if (quota.courseName) {
    showToast(`正在前往課表預約 ${quota.courseName}`, 'success')
    const isPrivate = getQuotaTypeLabel(quota) === '私教課'
    router.push({
      path: '/reservation',
      query: {
        keyword: quota.courseName,
        type: isPrivate ? 'PRIVATE' : 'GROUP'
      }
    })
  } else {
    router.push('/reservation')
  }
}

// [功能方法] clearFilters (清除filters)
// 為什麼要這麼做：清空當前的輸入表單或暫存狀態，將介面重置為初始狀態方便使用者重新輸入。
function clearFilters() {
  searchParams.value = { keyword: '', trainerId: '', expiryBefore: '' }
  selectedType.value = ''
}

// [計算屬性] ownedTrainers (ownedtrainers)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const ownedTrainers = computed(() => {
  const map = new Map()
  quotas.value.forEach(q => {
    const m = getQuotaCourse(q)
    const isPrivate = m ? isCoursePrivate(m) : true
    if (!isPrivate) return
    if (m?.trainer?.id) {
      map.set(m.trainer.id, {
        id: m.trainer.id,
        name: `${m.trainer.lastName || ''}${m.trainer.firstName || ''}`
      })
    }
  })
  return Array.from(map.values())
})

// [計算屬性] filteredQuotas (filteredquotas)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filteredQuotas = computed(() => quotas.value.filter(q => {
  const m = getQuotaCourse(q)
  const isPrivate = m ? isCoursePrivate(m) : true
  if (!isPrivate) return false

  if (searchQuery.value) {
    const q2 = searchQuery.value.trim().toLowerCase()
    if (!q.courseName?.toLowerCase().includes(q2)) return false
  }
  if (selectedTrainerId.value) {
    if (!m?.trainer || m.trainer.id !== Number(selectedTrainerId.value)) return false
  }
  if (expiryBefore.value) {
    // 永久有效（null）不在篩選範圍內，效期早於截止日才顯示
    if (!q.expiryDate) return false
    if (new Date(q.expiryDate) > new Date(expiryBefore.value)) return false
  }
  return true
}))

onMounted(async () => {
  try {
    const [quotasRes, reservationsRes] = await Promise.all([
      getMyQuotas(),
      getMyReservations(),
      coursesStore.loadCourses()
    ])
    quotas.value = quotasRes.data || []
    myReservations.value = reservationsRes.data?.items || []
  } catch (error) {
    console.error('Failed to load courses data:', error)
    showToast('載入課程資料失敗，請重試', 'error')
  } finally {
    loading.value = false
  }
})


</script>

<template>
  <div class="page-container">
    <div class="section-header">
      <span class="section-tag">MY COURSES</span>
      <h2 class="section-title">我的課程</h2>
      <p class="section-desc">管理您所擁有的課程額度、已預約及已上課的堂數統計</p>
    </div>

    <!-- 載入中 -->
    <div v-if="loading" class="spinner-container">
      <div class="spinner"></div>
    </div>

    <div v-else>
      <!-- Tabs 切換 -->


      <!-- ======================== Tab 1: 課程方案額度 ======================== -->
      <div v-if="activeTab === 'quotas'">
        <div v-if="quotas.length === 0" class="empty-state card">
          <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" style="color: var(--text-muted);">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
          </svg>
          <h3>目前尚無任何課程方案額度</h3>
          <p>前往課程方案商城選購專屬的一對一教練課或團體課吧！</p>
          <RouterLink to="/courses" class="btn btn-primary">瀏覽課程方案</RouterLink>
        </div>

        <div v-else>
          <!-- 篩選列 -->
          <CourseSearchBar
            v-model="searchParams"
            :show-keyword="true"
            :show-trainer="true"
            :show-category="false"
            :show-type="false"
            :show-expiry-before="true"
          />

          <!-- 無篩選結果 -->
          <div v-if="filteredQuotas.length === 0" class="empty-state card">
            <h3>沒有符合條件的課程額度</h3>
            <p>請嘗試調整篩選條件</p>
            <button class="btn btn-outline" @click="clearFilters" type="button">重設篩選條件</button>
          </div>

          <!-- 額度卡片格線 -->
          <div v-else class="quota-grid">
            <div v-for="q in filteredQuotas" :key="q.id" class="quota-card card premium-card">
              <div class="card-accent-bar"></div>

              <div class="quota-header">
                <div class="quota-title-sec">
                  <h3 class="quota-name">{{ q.courseName }}</h3>
                  <div class="quota-badges-row">
                    <span class="badge badge-primary">購買方案: 共 {{ q.remainingCourse + q.reservedCourse + q.usedCourse }} 堂</span>
                    <CourseTypeBadge course-type="PRIVATE" />
                    <span v-if="getQuotaTrainerName(q)" class="badge badge-success">指導教練: {{ getQuotaTrainerName(q) }}</span>
                  </div>
                </div>
                <div class="expiry-info" :class="{ 'expiry-warn': isNearExpiry(q.expiryDate) }">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                  <span>{{ q.expiryDate ? '效期至 ' + formatDate(q.expiryDate) : '永久有效' }}</span>
                </div>
              </div>

              <!-- 進度條 -->
              <div class="progress-wrap">
                <div class="progress-bar">
                  <div class="prog-seg seg-used"    :style="{ width: getPercentage(q.usedCourse, q) + '%' }" title="已上課"></div>
                  <div class="prog-seg seg-reserved" :style="{ width: getPercentage(q.reservedCourse, q) + '%' }" title="預約中"></div>
                  <div class="prog-seg seg-remain"   :style="{ width: getPercentage(q.remainingCourse, q) + '%' }" title="可預約"></div>
                </div>
                <div class="progress-legend">
                  <span class="legend-item"><span class="dot dot-used"></span>已上課 {{ q.usedCourse }} 堂</span>
                  <span class="legend-item"><span class="dot dot-reserved"></span>預約中 {{ q.reservedCourse }} 堂</span>
                  <span class="legend-item highlight"><span class="dot dot-remain"></span>剩餘 {{ q.remainingCourse }} 堂</span>
                </div>
              </div>

              <!-- 操作列 -->
              <div class="quota-actions">
                <button
                  class="btn btn-primary"
                  :disabled="q.remainingCourse <= 0"
                  @click="handleBookCourse(q)"
                >
                  {{ q.remainingCourse > 0 ? '立即預約' : '額度已用完' }}
                </button>
                <button class="btn btn-ghost" @click="toggleHistory(q.id)" type="button">
                  {{ expandedHistories.has(q.id) ? '收起紀錄' : '上課紀錄 (' + getUsageRecords(q).length + ')' }}
                </button>
              </div>

              <!-- 上課紀錄 -->
              <div v-if="expandedHistories.has(q.id)" class="history-panel">
                <div v-if="getUsageRecords(q).length === 0" class="no-history">尚無上課紀錄</div>
                <div v-else class="history-list">
                  <div
                    v-for="r in getUsageRecords(q)"
                    :key="r.id"
                    :class="['history-row', r.status === 'COMPLETED' ? 'row-done' : 'row-pending']"
                  >
                    <span class="history-dot"></span>
                    <div class="history-info">
                      <span class="history-date">{{ formatReservationTimeRange(r) }}</span>
                      <span class="history-trainer">教練：{{ r.trainerName }}</span>
                    </div>
                    <span :class="['badge', r.status === 'COMPLETED' ? 'badge-success' : 'badge-warning']">
                      {{ r.status === 'COMPLETED' ? '已上課' : '預約中' }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>


    </div>
  </div>
</template>

<style scoped>
/* ── Tabs 切換 ── */
.course-tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 2px solid var(--border);
  margin-bottom: 2rem;
}
.tab-btn {
  background: none;
  border: none;
  padding: 0.7rem 1.4rem;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.tab-btn:hover { color: var(--primary); }
.tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.tab-count {
  background: var(--primary);
  color: #fff;
  font-size: 0.7rem;
  font-weight: 800;
  padding: 0.1rem 0.4rem;
  border-radius: 20px;
}

/* ── 空狀態 ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 4rem 2rem;
  text-align: center;
  border: 1px dashed var(--border);
}
.empty-state h3 { font-size: 1.1rem; font-weight: 700; }
.empty-state p { color: var(--text-secondary); font-size: 0.9rem; }

/* ── 篩選與排版 ── */
.form-label {
  font-size: 0.72rem;
  font-weight: 800;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.form-input {
  height: 38px;
  padding: 0 0.85rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
  background: var(--bg-soft);
  color: var(--text-primary);
  font-size: 0.85rem;
  font-weight: 600;
  outline: none;
  transition: border-color 0.2s;
  width: 100%;
  box-sizing: border-box;
}
.form-input:focus {
  border-color: var(--primary);
}
.clear-btn {
  position: absolute; right: 0.6rem; top: 50%; transform: translateY(-50%);
  background: none; border: none; color: var(--text-muted); cursor: pointer;
}

/* ── 分段控制 ── */
.segmented-control {
  display: flex; background: var(--bg-soft); border: 1px solid var(--border);
  border-radius: var(--radius-sm); padding: 0.2rem;
}
.seg-btn {
  flex: 1; background: none; border: none; padding: 0.35rem 0.75rem;
  font-size: 0.85rem; font-weight: 600; color: var(--text-secondary);
  border-radius: calc(var(--radius-sm) - 2px); cursor: pointer;
}
.seg-btn.active { background: var(--bg-section); color: var(--primary-dark); box-shadow: var(--shadow-sm); }

/* ── 課程方案額度格線 ── */
.quota-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 1.25rem; }
@media (max-width: 560px) {
  .quota-grid {
    grid-template-columns: 1fr;
  }
}
.quota-card { position: relative; padding-top: 1.5rem; display: flex; flex-direction: column; gap: 1rem; }
.quota-card.premium-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1.15rem;
  background: var(--bg-card, #ffffff);
  box-shadow: var(--shadow-md);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  max-width: 380px;
  width: 100%;
}
.quota-card.premium-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
}
.quota-title-sec {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}
.quota-badges-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  align-items: center;
}
.quota-card .badge {
  font-size: 0.72rem;
  padding: 0.2rem 0.5rem;
  border-radius: 6px;
  font-weight: 700;
}
.card-accent-bar {
  position: absolute; top: 0; left: 0; right: 0; height: 4px;
  background: linear-gradient(90deg, var(--primary), var(--accent));
  border-radius: var(--radius-md) var(--radius-md) 0 0;
}
.quota-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 0.75rem; }
.quota-name { font-size: 1.05rem; font-weight: 800; color: var(--primary-dark); margin: 0; }
.expiry-info { display: flex; align-items: center; gap: 0.3rem; font-size: 0.75rem; color: var(--text-muted); }
.expiry-warn { color: #B25E52; font-weight: 600; }

/* ── 進度條 ── */
.progress-wrap { display: flex; flex-direction: column; gap: 0.6rem; }
.progress-bar { height: 7px; background: var(--bg-soft); border-radius: 4px; display: flex; overflow: hidden; }
.prog-seg { height: 100%; transition: width 0.4s ease; }
.seg-used { background-color: var(--text-muted); }
.seg-reserved { background-color: var(--primary-light); }
.seg-remain { background-color: var(--primary); }
.progress-legend { display: flex; gap: 1rem; font-size: 0.75rem; color: var(--text-secondary); flex-wrap: wrap; }
.legend-item { display: flex; align-items: center; gap: 0.3rem; }
.legend-item.highlight { color: var(--primary-dark); font-weight: 700; }
.dot { width: 6px; height: 6px; border-radius: 50%; }
.dot-used { background: var(--text-muted); }
.dot-reserved { background: var(--primary-light); }
.dot-remain { background: var(--primary); }
.quota-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.quota-actions .btn {
  padding: 0.35rem 0.9rem !important;
  font-size: 0.8rem !important;
  font-weight: 700 !important;
  line-height: 1.2 !important;
  border-radius: var(--radius-sm) !important;
}

/* ── 上課紀錄 ── */
.history-panel { border-top: 1px solid var(--border); padding-top: 0.75rem; }
.no-history { text-align: center; color: var(--text-muted); font-size: 0.82rem; padding: 0.5rem 0; }
.history-list { display: flex; flex-direction: column; gap: 0.3rem; max-height: 200px; overflow-y: auto; }
.history-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 0.6rem; border-radius: var(--radius-sm); background: var(--bg-soft); font-size: 0.78rem; }
.history-dot { width: 7px; height: 7px; border-radius: 50%; }
.row-done .history-dot { background: var(--accent); }
.row-pending .history-dot { background: var(--primary-light); }
.history-info { flex: 1; display: flex; flex-direction: column; gap: 0.1rem; }
.history-date { font-weight: 700; color: var(--text-primary); }
.history-trainer { color: var(--text-secondary); font-size: 0.72rem; }

/* ── 預約課表 ── */
.schedule-section { margin-bottom: 1rem; }
.section-label { font-size: 1.05rem; font-weight: 800; color: var(--primary-dark); margin-bottom: 1rem; }
.schedule-list { display: flex; flex-direction: column; gap: 0.75rem; }
.schedule-card { display: flex; align-items: center; gap: 1.25rem; padding: 1.1rem 1.4rem; }
.schedule-card.past-card { opacity: 0.75; }

.date-col {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  min-width: 56px; padding: 0.5rem; background: var(--bg-soft); border: 1px solid var(--border);
  border-radius: var(--radius-sm); flex-shrink: 0;
}
.date-month { font-size: 0.7rem; font-weight: 700; color: var(--primary); }
.date-day { font-size: 1.4rem; font-weight: 900; color: var(--primary-dark); line-height: 1.1; }
.date-week { font-size: 0.65rem; color: var(--text-muted); }
.schedule-info { flex: 1; }
.schedule-course { font-size: 1rem; font-weight: 800; color: var(--primary-dark); margin: 0 0 0.4rem 0; }
.meta-pill {
  font-size: 0.78rem; color: var(--text-secondary); background: var(--bg-soft);
  border: 1px solid var(--border); padding: 0.15rem 0.55rem; border-radius: var(--radius-xl);
}
.schedule-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 0.6rem; }
.key-display {
  display: flex; align-items: center; gap: 0.4rem; background: var(--accent-light);
  border: 1px solid var(--primary-light); padding: 0.25rem 0.6rem; border-radius: var(--radius-sm);
}
.key-label { font-size: 0.72rem; font-weight: 700; color: var(--accent); }
.key-code { font-size: 0.88rem; font-weight: 800; color: var(--primary-dark); }

/* ── Modal 取消詳情 ── */
.cancel-body { display: flex; flex-direction: column; gap: 1rem; }
.cancel-summary { background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 0.85rem 1rem; display: flex; flex-direction: column; gap: 0.45rem; }
.summary-row { display: flex; gap: 0.75rem; font-size: 0.88rem; color: var(--text-primary); }
.summary-label { color: var(--text-secondary); min-width: 40px; font-weight: 600; }
.cancel-alert { border-radius: var(--radius-sm); padding: 0.75rem 1rem; font-size: 0.85rem; }
.cancel-alert.alert-info { background: #FAF6F0; border: 1px solid var(--primary-light); color: var(--accent); }
.cancel-alert.alert-warn { background: #fef2f2; border: 1px solid #fecaca; color: #B25E52; }

.spinner-container { display: flex; align-items: center; justify-content: center; padding: 4rem; }

/* ── 預約詳情樣式 ── */
.res-detail-body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.detail-course-title {
  font-size: 1.2rem;
  font-weight: 800;
  color: var(--text-primary);
  margin: 0;
}
.detail-desc-box {
  background: var(--bg-soft);
  border-left: 4px solid var(--primary);
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
}
.detail-desc-text {
  font-size: 0.9rem;
  color: var(--text-secondary);
  line-height: 1.5;
  margin: 0;
}
.detail-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  background: var(--bg-soft);
  padding: 0.75rem;
  border-radius: 8px;
}
.detail-info-grid .info-item {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.detail-info-grid .info-label {
  font-size: 0.72rem;
  color: var(--text-muted);
  text-transform: uppercase;
  font-weight: 600;
}
.detail-info-grid .info-value {
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
}
.detail-trainer-section {
  border-top: 1px dashed var(--border);
  padding-top: 0.75rem;
}
.detail-section-title {
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 0.4rem;
}
.detail-trainer-card {
  display: flex;
  gap: 0.75rem;
  background: var(--bg-soft);
  padding: 0.75rem;
  border-radius: 8px;
  align-items: flex-start;
}
.detail-trainer-avatar {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--primary);
  flex-shrink: 0;
}
.detail-trainer-details {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
.detail-trainer-name {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-primary);
}
.detail-trainer-exp, .detail-trainer-spec {
  font-size: 0.82rem;
  color: var(--text-secondary);
}
.detail-trainer-bio {
  font-size: 0.82rem;
  color: var(--text-muted);
  margin-top: 0.4rem;
  line-height: 1.4;
  border-top: 1px solid var(--border);
  padding-top: 0.4rem;
}

/* ── Premium Table Styles ── */
.table-container {
  overflow: hidden;
  border-radius: var(--radius-lg, 12px);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  background: var(--bg-section);
  min-height: 380px; /* 穩定頁面高度，避免切換子頁籤時頁面跳動抖動 */
}

.premium-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
  table-layout: fixed; /* 固定欄寬，防止內容字數不同導致欄位位移 */
}

.premium-table th {
  background: var(--bg-soft, #f8fafc);
  font-weight: 700;
  color: var(--text-secondary);
  padding: 0.75rem 0.85rem;
  border-bottom: 2px solid var(--border);
  text-align: center; /* 標題置中 */
}

.premium-table td {
  padding: 0.75rem 0.85rem;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
  color: var(--text-primary);
  word-break: break-all; /* 防止英文換行撐開欄位 */
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center; /* 內容置中 */
}

/* 課程名稱左對齊，便於閱讀 */
.premium-table th.col-left,
.premium-table td.col-left {
  text-align: left;
}

/* 表格內的按鈕與徽章縮小優化，避免過大 */
.premium-table .btn {
  padding: 0.2rem 0.45rem !important;
  font-size: 0.75rem !important;
  font-weight: 600 !important;
  line-height: 1.2 !important;
  border-radius: 4px !important;
}

.premium-table .badge {
  font-size: 0.72rem;
  padding: 0.15rem 0.4rem;
}

.premium-table tr:last-child td {
  border-bottom: none;
}

.premium-table tr:hover td {
  background-color: rgba(0, 0, 0, 0.015);
}

.premium-table tr.row-expired td {
  opacity: 0.65;
  background-color: var(--bg-soft, #f8fafc);
}

/* ── Sub Tab Controls ── */
.sub-tab-control {
  display: flex;
  background: var(--bg-soft, #f1f5f9);
  padding: 0.25rem;
  border-radius: 8px;
  width: fit-content;
}

.sub-tab-btn {
  background: none;
  border: none;
  padding: 0.4rem 0.9rem;
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-secondary);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.sub-tab-btn:hover {
  color: var(--text-primary);
}

.sub-tab-btn.active {
  background: var(--bg-section, #fff);
  color: var(--primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.key-display-inline {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  background: var(--bg-soft, #f8fafc);
  border: 1px solid var(--border);
  padding: 0.15rem 0.4rem;
  border-radius: var(--radius-sm);
  font-size: 0.78rem;
  color: var(--primary);
  font-weight: 700;
}

.key-label-sm {
  color: var(--text-muted);
  font-size: 0.72rem;
}
.key-code-sm {
  font-family: monospace;
}

@media (max-width: 768px) {
  .premium-table th, .premium-table td {
    padding: 0.6rem;
    font-size: 0.82rem;
  }
}

@media (max-width: 640px) {
  .schedule-card { flex-wrap: wrap; }
  .schedule-actions { align-items: flex-start; width: 100%; }
  .quota-grid { grid-template-columns: 1fr; }
  .filter-row { flex-direction: column; }
  .detail-info-grid { grid-template-columns: 1fr; }
  .detail-trainer-card { flex-direction: column; align-items: center; text-align: center; }
}
</style>