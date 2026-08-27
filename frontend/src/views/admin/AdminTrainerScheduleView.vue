<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { getTrainers, getTrainerCoachSlots } from '@/api/adminTrainer.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'

// 教練可服務狀態；對應後端 TrainerStatus
const TRAINER_STATUS_MAP = {
  ACTIVE: { label: '可服務', className: 'status-active' },
  UNAVAILABLE: { label: '暫停服務', className: 'status-suspended' },
}

// 時段狀態顯示，寫法與配色比照 TrainerScheduleView.vue 的 slotStatusInfo——
// BOOKED 依關聯的預約狀態再細分成「待核銷」／「已完成」，AVAILABLE／CANCELLED 沒有
// bookingStatus 可看，直接用 status 本身對照
const SLOT_STATUS_MAP = {
  AVAILABLE: { label: '可預約', className: 'status-available' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

const trainers = ref([])
const listLoading = ref(true)
const listError = ref(null)

const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

const keyword = ref('')
const statusFilter = ref('')

let searchDebounceTimer = null

// 排課面板：唯讀，只顯示指定教練的時段列表，不涉及任何建立/修改/取消動作
const viewing = ref(null) // null 代表關閉；開啟時存 { trainerId, displayName }

const slots = ref([])
const slotsLoading = ref(false)
const slotsPage = ref(0)
const slotsSize = ref(10)
const slotsTotalItems = ref(0)
const slotsTotalPages = ref(0)

const slotStatusFilter = ref('')
const slotDateFrom = ref('')
const slotDateTo = ref('')

const emptyStateMessage = computed(() =>
  keyword.value || statusFilter.value
    ? '查無符合條件的教練'
    : '目前還沒有任何教練資料。',
)

function trainerStatusInfo(status) {
  return TRAINER_STATUS_MAP[status] ?? { label: status, className: '' }
}

// BOOKED 時段要進一步依 bookingStatus 區分「待核銷」跟「已完成」，
// 其他狀態（AVAILABLE／CANCELLED）沒有 bookingStatus 可看，直接沿用 SLOT_STATUS_MAP
function slotStatusInfo(slot) {
  if (slot.status === 'BOOKED' && slot.bookingStatus === 'PENDING') {
    return { label: '已預約（待核銷）', className: 'status-booked' }
  }
  if (slot.status === 'BOOKED' && slot.bookingStatus === 'COMPLETED') {
    return { label: '已完成', className: 'status-completed' }
  }
  return SLOT_STATUS_MAP[slot.status] ?? { label: slot.status, className: '' }
}

function formatDateTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(
    d.getDate(),
  ).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function fetchTrainers() {
  listLoading.value = true
  listError.value = null
  try {
    const { data } = await getTrainers({
      keyword: keyword.value,
      status: statusFilter.value,
      page: page.value,
      size: size.value,
    })
    // 後端回的是 Spring Page：content／totalElements／totalPages
    trainers.value = data.content
    totalItems.value = data.totalElements
    totalPages.value = data.totalPages
  } catch (e) {
    listError.value = e.message
  } finally {
    listLoading.value = false
  }
}

onMounted(fetchTrainers)

// 搜尋字串 debounce 400ms，避免每敲一個字就打一次 API；
// 換條件代表結果集變了，page 一律重設為 0，免得卡在不存在的頁碼
watch(keyword, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchTrainers()
  }, 400)
})

watch(statusFilter, () => {
  page.value = 0
  fetchTrainers()
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchTrainers()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchTrainers()
}

function openSchedule(trainer) {
  viewing.value = { trainerId: trainer.trainerId, displayName: trainer.displayName }
  slotsPage.value = 0
  slotStatusFilter.value = ''
  slotDateFrom.value = ''
  slotDateTo.value = ''
  fetchTrainerSlots()
}

async function fetchTrainerSlots() {
  if (!viewing.value) return
  slotsLoading.value = true
  try {
    const { data } = await getTrainerCoachSlots(viewing.value.trainerId, {
      page: slotsPage.value,
      size: slotsSize.value,
      status: slotStatusFilter.value,
      dateFrom: slotDateFrom.value,
      dateTo: slotDateTo.value,
    })
    slots.value = data.items
    slotsTotalItems.value = data.totalItems
    slotsTotalPages.value = data.totalPages
  } catch (e) {
    console.error('查詢教練排課失敗', e)
    slots.value = []
  } finally {
    slotsLoading.value = false
  }
}

watch([slotStatusFilter, slotDateFrom, slotDateTo], () => {
  slotsPage.value = 0
  fetchTrainerSlots()
})

function handleSlotsPageChange(newPage) {
  slotsPage.value = newPage
  fetchTrainerSlots()
}

function handleSlotsSizeChange(newSize) {
  slotsSize.value = newSize
  slotsPage.value = 0
  fetchTrainerSlots()
}

function closeSchedule() {
  viewing.value = null
  slots.value = []
  slotsPage.value = 0
  slotsTotalItems.value = 0
  slotsTotalPages.value = 0
}
</script>

<template>
  <div class="page-container admin-trainer-schedule-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">教練排課查詢</h1>
    <p class="section-desc">查詢教練建立的私教課時段，以及每個時段目前的預約狀況（唯讀，不可異動）。</p>

    <!-- 篩選列 -->
    <div class="filters card">
      <label class="filter filter--search">
        <span class="filter__label">搜尋</span>
        <input
          v-model="keyword"
          type="search"
          class="filter__input"
          placeholder="姓名／Email／專長"
        />
      </label>

      <label class="filter">
        <span class="filter__label">狀態</span>
        <select v-model="statusFilter" class="filter__input">
          <option value="">全部</option>
          <option value="ACTIVE">可服務</option>
          <option value="UNAVAILABLE">暫停服務</option>
        </select>
      </label>

      <span class="filters__total">共 {{ totalItems }} 位教練</span>
    </div>

    <OperationMessage :message="listError" />

    <div class="card table-card">
      <div v-if="listLoading" class="spinner"></div>
      <p v-else-if="trainers.length === 0" class="empty-hint">{{ emptyStateMessage }}</p>
      <table v-else class="data-table">
        <thead>
          <tr>
            <th>教練編號</th>
            <th>姓名</th>
            <th>Email</th>
            <th>專長</th>
            <th>狀態</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in trainers" :key="t.trainerId">
            <td>{{ t.trainerId }}</td>
            <td>{{ t.displayName }}</td>
            <td class="col-email">{{ t.email }}</td>
            <td>{{ t.specialty || '—' }}</td>
            <td>
              <span class="status-badge" :class="trainerStatusInfo(t.status).className">
                {{ trainerStatusInfo(t.status).label }}
              </span>
            </td>
            <td class="col-actions">
              <button type="button" class="btn btn-sm" @click="openSchedule(t)">查看排課</button>
            </td>
          </tr>
        </tbody>
      </table>

      <Pagination
        v-if="totalPages > 0"
        :page="page"
        :size="size"
        :total-pages="totalPages"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      />
    </div>

    <!-- 排課面板：唯讀，只顯示時段列表與其預約狀況，沒有任何編輯/取消按鈕 -->
    <div v-if="viewing" class="edit-overlay" @click.self="closeSchedule">
      <section class="edit-panel card">
        <header class="edit-panel__header">
          <h2>{{ viewing.displayName }} 的排課</h2>
          <button type="button" class="edit-panel__close" @click="closeSchedule">×</button>
        </header>

        <div class="filters filters--panel">
          <label class="filter">
            <span class="filter__label">狀態</span>
            <select v-model="slotStatusFilter" class="filter__input">
              <option value="">全部</option>
              <option value="AVAILABLE">可預約</option>
              <option value="PENDING">已預約（待核銷）</option>
              <option value="COMPLETED">已完成</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </label>
          <label class="filter">
            <span class="filter__label">起始日期</span>
            <input v-model="slotDateFrom" type="date" class="filter__input" :max="slotDateTo || undefined" />
          </label>
          <label class="filter">
            <span class="filter__label">結束日期</span>
            <input v-model="slotDateTo" type="date" class="filter__input" :min="slotDateFrom || undefined" />
          </label>
        </div>

        <section class="quota-section">
          <div v-if="slotsLoading" class="spinner"></div>
          <p v-else-if="slots.length === 0" class="quota-empty">
            此教練目前沒有符合條件的排課時段
          </p>
          <template v-else>
            <table class="quota-table">
              <thead>
                <tr>
                  <th>開始時間</th>
                  <th>結束時間</th>
                  <th>狀態</th>
                  <th>預約會員</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in slots" :key="s.id">
                  <td>{{ formatDateTime(s.startAt) }}</td>
                  <td>{{ formatDateTime(s.endAt) }}</td>
                  <td>
                    <span class="status-badge" :class="slotStatusInfo(s).className">
                      {{ slotStatusInfo(s).label }}
                    </span>
                  </td>
                  <td>{{ s.memberName || '—' }}</td>
                </tr>
              </tbody>
            </table>
            <Pagination
              :page="slotsPage"
              :size="slotsSize"
              :total-pages="slotsTotalPages"
              @update:page="handleSlotsPageChange"
              @update:size="handleSlotsSizeChange"
            />
          </template>
        </section>
      </section>
    </div>
  </div>
</template>

<style scoped>
.admin-trainer-schedule-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

/* ---- 篩選列 ---- */
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 1.5rem;
  padding: 1.5rem 1.75rem;
  margin-bottom: 1.5rem;
}

.filters--panel {
  padding: 0 0 1rem;
  margin-bottom: 0;
  border-bottom: 1px solid var(--border);
}

.filter {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.filter__label {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.filter__input {
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
  min-width: 8rem;
}

.filter--search .filter__input {
  min-width: 14rem;
}

.filters__total {
  margin-left: auto;
  font-size: 0.9rem;
  color: var(--text-secondary);
}

/* ---- 表格 ---- */
.table-card {
  padding: 1.5rem 1.75rem;
  overflow-x: auto;
}

.data-table thead tr:first-child th:first-child {
  border-top-left-radius: var(--radius-sm);
  border-bottom-left-radius: var(--radius-sm);
}
.data-table thead tr:first-child th:last-child {
  border-top-right-radius: var(--radius-sm);
  border-bottom-right-radius: var(--radius-sm);
}

.data-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 0.95rem;
}

.data-table th,
.data-table td {
  padding: 0.9rem 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.data-table th:first-child,
.data-table td:first-child {
  padding-left: 0.85rem;
}
.data-table th:last-child,
.data-table td:last-child {
  padding-right: 0.85rem;
}

.data-table th {
  font-size: 0.85rem;
  color: var(--text-secondary);
  font-weight: 700;
  background: var(--bg-soft);
}

.data-table tbody tr:last-child td {
  border-bottom: none;
}

.col-email {
  max-width: 16rem;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-actions {
  display: flex;
  gap: 0.6rem;
}

.empty-hint {
  color: var(--text-secondary);
  padding: 1rem 0;
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
}

.status-active {
  background: #e3f4e6;
  color: #256b33;
}

.status-suspended {
  background: #fae3e0;
  color: #9b2c1e;
}

.status-available {
  background: #e8f3e6;
  color: #3f7a3a;
}

.status-booked {
  background: #f5ecd8;
  color: var(--accent-hover);
}

.status-cancelled {
  background: #f1ecea;
  color: var(--text-muted);
}

.status-completed {
  background: #e3ecf5;
  color: #38618c;
}

/* ---- 排課面板 ---- */
.edit-overlay {
  position: fixed;
  inset: 0;
  background: rgba(30, 24, 20, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 200;
}

.edit-panel {
  width: min(46rem, 100%);
  max-height: 88vh;
  overflow-y: auto;
  padding: 1.25rem 1.5rem 1.5rem;
}

.edit-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.edit-panel__header h2 {
  margin: 0;
  font-size: 1.15rem;
}

.edit-panel__close {
  background: none;
  border: none;
  font-size: 1.6rem;
  line-height: 1;
  cursor: pointer;
  color: var(--text-secondary);
}

.quota-section {
  margin-top: 1rem;
}
.quota-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}
.quota-table th,
.quota-table td {
  padding: 0.5rem;
  text-align: left;
  border-bottom: 1px solid var(--border-color, #eee);
}
.quota-empty {
  color: var(--text-secondary, #666);
  padding: 1rem 0;
}
</style>
