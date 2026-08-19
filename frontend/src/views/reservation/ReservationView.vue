<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  getTrainers,
  getTrainerSchedules,
  createBooking,
  getMyBookings,
  cancelBooking,
} from '@/api/coachBooking.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'

// 預約狀態對照表：後端回傳 PENDING/COMPLETED/CANCELLED，畫面顯示中文與顏色
const BOOKING_STATUS_MAP = {
  PENDING: { label: '待上課', className: 'status-pending' },
  COMPLETED: { label: '已完成', className: 'status-completed' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

const trainers = ref([])
const trainersLoading = ref(true)

const selectedTrainerId = ref(null)
const slots = ref([])
const slotsLoading = ref(false)

// 正在送出預約的時段 id；用來讓「預約」按鈕在等待回應期間顯示 loading、避免重複點擊
const bookingSlotId = ref(null)

// 我的預約列表，跟教練清單、時段列表的 loading 各自獨立、互不影響
const myBookings = ref([])
const myBookingsLoading = ref(true)

// 預約／取消預約的就地錯誤訊息，顯示在「我的預約」區塊上方
const bookingActionMessage = ref('')

// 「我的預約」的分頁狀態；這個區塊沒有搜尋需求
const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

// 依開始時間排序，未來時段在前（跟 TrainerScheduleView 的 sortedSlots 同樣的排序邏輯）
const sortedSlots = computed(() =>
  [...slots.value].sort((a, b) => new Date(a.startAt) - new Date(b.startAt)),
)

// confirm() 訊息要帶教練姓名，從目前選中的教練 id 反查清單裡的資料
const selectedTrainer = computed(
  () => trainers.value.find((t) => t.trainerId === selectedTrainerId.value) ?? null,
)

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

// 只取時間部分（HH:mm），給 confirm() 訊息裡的結束時間用，不用重複顯示日期
function timeOnly(iso) {
  const [, timePart] = iso.split('T')
  return (timePart || '').slice(0, 5)
}

async function fetchTrainers() {
  trainersLoading.value = true
  try {
    const res = await getTrainers()
    // 新 API 會回傳 UNAVAILABLE 教練，這裡過濾只留 ACTIVE，維持跟舊 API（只回傳在職教練）一致的既有行為
    trainers.value = res.data.filter((trainer) => trainer.status === 'ACTIVE')
  } finally {
    trainersLoading.value = false
  }
}

async function fetchTrainerSchedules(trainerId) {
  slotsLoading.value = true
  try {
    const res = await getTrainerSchedules(trainerId)
    // 這個頁面只做瀏覽可預約時段，BOOKED／CANCELLED 的時段不在會員視角顯示
    slots.value = res.data.filter((slot) => slot.status === 'AVAILABLE')
  } finally {
    slotsLoading.value = false
  }
}

async function fetchMyBookings() {
  myBookingsLoading.value = true
  try {
    const res = await getMyBookings({ page: page.value, size: size.value })
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

// 呼叫取消預約 API；若後端回 CONFIRMATION_REQUIRED（距上課不到 72 小時，取消視同消耗額度），
// 跳出瀏覽器原生確認視窗，使用者按確定就帶著 acknowledgeConsumption: true 重送一次，
// 按取消則不重送，讓例外往外拋出，維持該筆預約原狀（寫法比照 TrainerScheduleView 的 submitCancel）
async function submitCancelBooking(bookingId, acknowledgeConsumption = false) {
  try {
    await cancelBooking(bookingId, acknowledgeConsumption)
  } catch (err) {
    const { code, message } = err.response?.data || {}
    if (code === 'CONFIRMATION_REQUIRED' && !acknowledgeConsumption && window.confirm(message)) {
      await submitCancelBooking(bookingId, true)
      return
    }
    throw err
  }
}

// 點擊「取消」：取消成功後刷新我的預約列表；若被取消的預約剛好是目前選中教練的，
// 該時段有機會變回可預約狀態，一併刷新時段列表
async function handleCancelBooking(booking) {
  bookingActionMessage.value = ''
  try {
    await submitCancelBooking(booking.id)
    await fetchMyBookings()
    if (selectedTrainerId.value === booking.trainerId) {
      await fetchTrainerSchedules(selectedTrainerId.value)
    }
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      bookingActionMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點選教練：切換選取狀態、清掉前一位教練的時段列表，再重新抓這位教練的時段
function selectTrainer(trainer) {
  selectedTrainerId.value = trainer.trainerId
  slots.value = []
  fetchTrainerSchedules(trainer.trainerId)
}

// 點擊「預約」：先用瀏覽器原生 confirm() 做二次確認，讓使用者看清楚自己在約什麼堂課，
// 確定才送出請求；成功後把核銷碼顯示給使用者，並刷新列表讓已被約走的時段消失，
// 失敗則顯示錯誤訊息、維持列表原狀，讓使用者可以再試一次或選別的時段
async function handleBook(slot) {
  const trainerLabel = selectedTrainer.value?.displayName ?? ''
  const confirmed = window.confirm(
    `確定要預約 ${trainerLabel} ${formatDateTime(slot.startAt)}~${timeOnly(slot.endAt)} 這堂課嗎？`,
  )
  if (!confirmed) return

  bookingActionMessage.value = ''
  bookingSlotId.value = slot.id
  try {
    const res = await createBooking(slot.id)
    alert(`預約成功！核銷碼：${res.data.checkInKey}，上課時請出示給教練核銷`)
    await fetchTrainerSchedules(selectedTrainerId.value)
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      bookingActionMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    bookingSlotId.value = null
  }
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

    <div v-if="trainersLoading" class="spinner"></div>

    <template v-else>
      <p v-if="trainers.length === 0" class="empty-state">目前沒有在職教練。</p>

      <div v-else class="trainer-grid">
        <button
          v-for="trainer in trainers"
          :key="trainer.trainerId"
          type="button"
          class="trainer-card"
          :class="{ 'trainer-card-selected': trainer.trainerId === selectedTrainerId }"
          @click="selectTrainer(trainer)"
        >
          <h3 class="trainer-name">{{ trainer.displayName }}</h3>
          <p class="trainer-specialty">{{ trainer.specialty }}</p>
          <p class="trainer-experience">{{ trainer.experience }}</p>
        </button>
      </div>
    </template>

    <div v-if="selectedTrainerId" class="schedule-section">
      <h3 class="schedule-title">可預約時段</h3>

      <div v-if="slotsLoading" class="spinner"></div>

      <template v-else>
        <p v-if="sortedSlots.length === 0" class="empty-state">這位教練目前沒有可預約的時段</p>

        <table v-else class="slot-table">
          <thead>
            <tr>
              <th>開始時間</th>
              <th>結束時間</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="slot in sortedSlots" :key="slot.id">
              <td>{{ formatDateTime(slot.startAt) }}</td>
              <td>{{ formatDateTime(slot.endAt) }}</td>
              <td>
                <button
                  class="btn btn-accent"
                  type="button"
                  :disabled="bookingSlotId === slot.id"
                  @click="handleBook(slot)"
                >
                  {{ bookingSlotId === slot.id ? '預約中…' : '預約' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </div>

    <div class="my-bookings-section">
      <h3 class="schedule-title">我的預約</h3>

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

.trainer-card-selected {
  border-color: var(--accent);
  background: var(--bg-soft);
  box-shadow: var(--shadow-hover);
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

.schedule-section {
  margin-top: 1rem;
}

.my-bookings-section {
  margin-top: 2.5rem;
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
