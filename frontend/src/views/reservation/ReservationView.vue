<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  getTrainers,
  getMyBookings,
  cancelBooking,
} from '@/api/coachBooking.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'
import { getMyQuotas } from '@/api/member/booking.js'

// 預約狀態對照表：後端回傳 PENDING/COMPLETED/CANCELLED，畫面顯示中文與顏色
const BOOKING_STATUS_MAP = {
  PENDING: { label: '待上課', className: 'status-pending' },
  COMPLETED: { label: '已完成', className: 'status-completed' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

const router = useRouter()
const route = useRoute()

const trainers = ref([])
const trainersLoading = ref(true)

// 選擇教練／我的預約 頁籤切換；純前端狀態，兩邊資料都已經在 onMounted 抓過，
// 切換不重打 API。這頁本身 requiresAuth: true（進得來就一定是登入會員），
// 不需要像 GroupClassView 那樣額外判斷 auth.isMember 才顯示切換器。
const viewMode = ref('trainers')

watch(
  () => route.query.tab,
  (tab) => {
    if (tab === 'bookings') {
      viewMode.value = 'bookings'
    } else {
      viewMode.value = 'trainers'
    }
  },
  { immediate: true }
)

// 我的預約列表，跟教練清單的 loading 各自獨立、互不影響
const myBookings = ref([])
const myBookingsLoading = ref(true)

// 預約／取消預約的就地錯誤訊息，顯示在「我的預約」區塊上方
const bookingActionMessage = ref('')

// 「我的預約」的分頁狀態
const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

// 狀態篩選：空字串代表「全部」，對應後端 CoachBookingStatus（PENDING/COMPLETED/CANCELLED）
const statusFilter = ref('')
// 上課日期區間篩選（yyyy-MM-dd），任一為空代表不限制該端
const dateFrom = ref('')
const dateTo = ref('')

// 我的預約列表也依開始時間排序
const sortedBookings = computed(() =>
  [...myBookings.value].sort((a, b) => new Date(a.startAt) - new Date(b.startAt)),
)

function bookingStatusInfo(status) {
  return BOOKING_STATUS_MAP[status] || { label: status, className: '' }
}

// 我的預約只有 trainerId，姓名要從已經抓到的教練清單反查，不用再多打一次 API；
// 查不到（例如教練已離職不在在職清單裡）就顯示代號，至少不會整列空白
function trainerName(trainerId) {
  return trainers.value.find((t) => t.trainerId === trainerId)?.displayName ?? `教練 #${trainerId}`
}

function formatDateTime(value) {
  if (!value) return ''
  const d = new Date(value)
  return d.toLocaleString('zh-TW', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

async function fetchTrainers() {
  trainersLoading.value = true
  try {
    const [trainersRes, quotasRes] = await Promise.all([
      getTrainers(),
      getMyQuotas()
    ])
    const activeQuotas = quotasRes.data || []
    const eligibleTrainerIds = activeQuotas
      .filter(q => q.remainingCourse > 0)
      .map(q => q.trainer?.id)
      .filter(Boolean)

    // 新 API 會回傳 UNAVAILABLE 教練，這裡過濾只留 ACTIVE 且擁有名下剩餘額度的教練
    trainers.value = trainersRes.data.filter((trainer) => 
      trainer.status === 'ACTIVE' && eligibleTrainerIds.includes(trainer.trainerId)
    )
  } finally {
    trainersLoading.value = false
  }
}

async function fetchMyBookings() {
  myBookingsLoading.value = true
  try {
    const res = await getMyBookings({
      page: page.value,
      size: size.value,
      status: statusFilter.value,
      dateFrom: dateFrom.value,
      dateTo: dateTo.value,
    })
    myBookings.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    myBookingsLoading.value = false
  }
}

function handlePageChange(newPage) {
  page.value = newPage
  fetchMyBookings()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchMyBookings()
}

// 狀態／日期區間一變動就重新查詢，並把 page 重設為 0，避免卡在篩選後不存在的頁碼
watch([statusFilter, dateFrom, dateTo], () => {
  page.value = 0
  fetchMyBookings()
})

// 呼叫取消預約 API；若後端回 CONFIRMATION_REQUIRED（距上課不到 72 小時，取消視同消耗額度），
// 跳出瀏覽器原生確認視窗，使用者按確定就帶著 acknowledgeConsumption: true 重送一次，
// 按取消則不重送，讓例外往外拋出，維持該筆預約原狀（寫法比照 TrainerScheduleView 的 submitCancel）
async function submitCancelBooking(bookingId, acknowledgeConsumption = false) {
  try {
    await cancelBooking(bookingId, acknowledgeConsumption)
  } catch (err) {
    // 攔截器 reject 的是 normalizeApiError() 正規化後的物件（{code, message, needsConfirmation, raw…}），
    // 不是原始的 axios error——所以這裡不能讀 err.response.data，那永遠是 undefined，
    // 確認框就再也不會跳出來，使用者只會看到一行「請確認」的錯誤訊息卻無從確認。
    if (err.needsConfirmation && !acknowledgeConsumption && window.confirm(err.message)) {
      await submitCancelBooking(bookingId, true)
      return
    }
    throw err
  }
}

// 點擊「取消」：取消成功後刷新我的預約列表
async function handleCancelBooking(booking) {
  bookingActionMessage.value = ''
  try {
    await submitCancelBooking(booking.id)
    await fetchMyBookings()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      bookingActionMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點選教練：導到該教練的可預約時段獨立頁面
function goToTrainerSlots(trainer) {
  router.push(`/reservation/trainer/${trainer.trainerId}`)
}

onMounted(() => {
  fetchTrainers()
  fetchMyBookings()
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">RESERVATION</span>
    <h2 class="section-title">預約課程</h2>
    <p class="section-desc">選擇教練，查看目前可預約的私教課時段。</p>

    <div class="view-switch" role="tablist" aria-label="檢視切換">
      <button
        type="button"
        role="tab"
        class="view-tab"
        :class="{ 'view-tab--active': viewMode === 'trainers' }"
        :aria-selected="viewMode === 'trainers'"
        @click="viewMode = 'trainers'"
      >
        選擇教練
      </button>
      <button
        type="button"
        role="tab"
        class="view-tab"
        :class="{ 'view-tab--active': viewMode === 'bookings' }"
        :aria-selected="viewMode === 'bookings'"
        @click="viewMode = 'bookings'"
      >
        我的預約
      </button>
    </div>

    <template v-if="viewMode === 'trainers'">
      <div v-if="trainersLoading" class="spinner"></div>

      <template v-else>
        <p v-if="trainers.length === 0" class="empty-state">目前沒有在職教練。</p>

        <div v-else class="trainer-grid">
          <button
            v-for="trainer in trainers"
            :key="trainer.trainerId"
            type="button"
            class="trainer-card"
            @click="goToTrainerSlots(trainer)"
          >
            <h3 class="trainer-name">{{ trainer.displayName }}</h3>
            <p class="trainer-specialty">{{ trainer.specialty }}</p>
            <p class="trainer-experience">{{ trainer.experience }}</p>
          </button>
        </div>
      </template>
    </template>

    <div v-if="viewMode === 'bookings'" class="my-bookings-section">
      <h3 class="schedule-title">我的預約</h3>

      <div class="filter-row">
        <div class="filter-field">
          <label for="myBookingsStatus">狀態</label>
          <select id="myBookingsStatus" v-model="statusFilter">
            <option value="">全部</option>
            <option value="PENDING">待上課</option>
            <option value="COMPLETED">已完成</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </div>
        <div class="filter-field">
          <label for="myBookingsDateFrom">上課日期（從）</label>
          <input id="myBookingsDateFrom" v-model="dateFrom" type="date" :max="dateTo || undefined" />
        </div>
        <div class="filter-field">
          <label for="myBookingsDateTo">上課日期（到）</label>
          <input id="myBookingsDateTo" v-model="dateTo" type="date" :min="dateFrom || undefined" />
        </div>
      </div>

      <OperationMessage :message="bookingActionMessage" />

      <div v-if="myBookingsLoading" class="spinner"></div>

      <template v-else>
        <p v-if="sortedBookings.length === 0" class="empty-state">目前還沒有任何預約紀錄。</p>

        <table v-else class="slot-table">
          <thead>
            <tr>
              <th>教練</th>
              <th>開始時間</th>
              <th>結束時間</th>
              <th>狀態</th>
              <th>核銷碼</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="booking in sortedBookings" :key="booking.id">
              <td>{{ trainerName(booking.trainerId) }}</td>
              <td>{{ formatDateTime(booking.startAt) }}</td>
              <td>{{ formatDateTime(booking.endAt) }}</td>
              <td>
                <span class="status-badge" :class="bookingStatusInfo(booking.status).className">
                  {{ bookingStatusInfo(booking.status).label }}
                </span>
              </td>
              <td>
                <span v-if="booking.status === 'PENDING'" class="checkin-key">
                  {{ booking.checkInKey }}
                </span>
                <span v-else>—</span>
              </td>
              <td>
                <button
                  v-if="booking.status === 'PENDING'"
                  class="btn btn-outline"
                  type="button"
                  @click="handleCancelBooking(booking)"
                >
                  取消
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <Pagination
          :page="page"
          :total-pages="totalPages"
          :size="size"
          @update:page="handlePageChange"
          @update:size="handleSizeChange"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.view-switch {
  display: flex;
  gap: 0.25rem;
  padding: 0.25rem;
  border-radius: var(--radius-sm);
  background: #e6d8c6;
  flex-shrink: 0;
  width: fit-content;
  margin-bottom: 1.25rem;
}

.view-tab {
  padding: 0.5rem 1.1rem;
  border: none;
  border-radius: calc(var(--radius-sm) - 2px);
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
}

.view-tab:hover {
  color: var(--primary-dark);
}

.view-tab--active {
  background: #f2e8dc;
  color: var(--primary-dark);
}

.trainer-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1rem;
  margin-bottom: 2rem;
}

.trainer-card {
  display: block;
  width: 100%;
  text-align: left;
  padding: 1.25rem;
  background: var(--bg-section);
  border: 1.5px solid var(--border);
  border-radius: var(--radius-md);
  font: inherit;
  color: inherit;
  cursor: pointer;
  transition: var(--transition);
}

.trainer-card:hover {
  border-color: var(--accent);
}

.trainer-name {
  margin: 0 0 0.5rem;
  font-size: 1.05rem;
  color: var(--text-primary);
}

.trainer-specialty {
  margin: 0 0 0.35rem;
  font-size: 0.9rem;
  font-weight: 700;
  color: var(--accent-hover);
}

.trainer-experience {
  margin: 0;
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.my-bookings-section {
  margin-top: 2.5rem;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 1rem;
  margin-bottom: 1rem;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.filter-field label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.filter-field select,
.filter-field input {
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
}

.schedule-title {
  margin: 0 0 1rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
}

.status-pending {
  background: #e8f3e6;
  color: #3f7a3a;
}

.status-completed {
  background: #e3ecf5;
  color: #38618c;
}

.status-cancelled {
  background: #f1ecea;
  color: var(--text-muted);
}

.checkin-key {
  font-family: 'Courier New', monospace;
  font-weight: 600;
  letter-spacing: 0.05em;
}

.empty-state {
  color: var(--text-muted);
  padding: 2rem 0;
}

.slot-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.slot-table th,
.slot-table td {
  text-align: left;
  padding: 0.85rem 1.25rem;
  border-bottom: 1px solid var(--border);
}

.slot-table th {
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 700;
  background: var(--bg-soft);
}

.slot-table tbody tr:last-child td {
  border-bottom: none;
}
</style>
