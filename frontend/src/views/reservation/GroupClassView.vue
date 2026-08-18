<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { getUpcomingSessions, bookSession, getMyBookings, cancelBooking } from '@/api/groupClass.js'
import { useAuthStore } from '@/stores/auth.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'
import { useFeedback } from '@/composables/useFeedback.js'
import Pagination from '@/components/Pagination.vue'
import FullCalendar from '@fullcalendar/vue3'
import timeGridPlugin from '@fullcalendar/timegrid'
import dayGridPlugin from '@fullcalendar/daygrid'
import zhTwLocale from '@fullcalendar/core/locales/zh-tw'

// 我的報名狀態對照表：後端回傳 BOOKED/CANCELLED，畫面顯示中文與顏色
const MY_BOOKING_STATUS_MAP = {
  BOOKED: { label: '已報名', className: 'status-booked' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

// 成功提示用 toast 而非 window.alert——alert 會阻斷畫面、還要使用者多按一次確定，
// 對「操作已完成」這種不需要決策的訊息太重（用法比照 ProductDetail.vue）
const { feedback, showFeedback } = useFeedback(2500)

const sessions = ref([])
const loading = ref(true)

// 我的團課報名列表，跟行事曆的 loading 各自獨立、互不影響
const myBookings = ref([])
const myBookingsLoading = ref(true)

// 正在送出取消的報名 id；用來讓「取消」按鈕在等待回應期間顯示 loading、避免重複點擊
const cancellingBookingId = ref(null)

// 目前選中的教室分頁；'all' 代表「所有教室」，純前端篩選，不重打 API
const selectedRoom = ref('all')

// 詳情卡 modal 狀態：點擊行事曆事件時開啟，selectedSession 存整筆場次資料供 modal 顯示與報名用
const isDetailModalOpen = ref(false)
const selectedSession = ref(null)
const bookingSubmitting = ref(false)
// 詳情卡報名的就地錯誤訊息，顯示在 modal 裡
const bookingError = ref('')

// 取消團課報名的就地錯誤訊息，顯示在「我的團課報名」區塊上方
const myBookingsMessage = ref('')

// 「我的團課報名」的分頁與搜尋狀態
const page = ref(0)
const size = ref(10)
const search = ref('')
const totalItems = ref(0)
const totalPages = ref(0)
let searchDebounceTimer = null

// 依開始時間排序；status 為 CANCELLED 的場次理論上後端不會回傳，這裡仍加一層防呆篩選
const visibleSessions = computed(() =>
  [...sessions.value]
    .filter((session) => session.status !== 'CANCELLED')
    .sort((a, b) => new Date(a.startAt) - new Date(b.startAt)),
)

// 從目前抓到的場次動態取出不重複的教室名稱，不寫死教室清單
const rooms = computed(() => [...new Set(visibleSessions.value.map((session) => session.roomName))])

const roomFilteredSessions = computed(() =>
  selectedRoom.value === 'all'
    ? visibleSessions.value
    : visibleSessions.value.filter((session) => session.roomName === selectedRoom.value),
)

// 我的團課報名列表依開始時間排序，跟行事曆的排序邏輯一致
// 後端已依「狀態→開課時間」排好（BOOKED 在前），這裡沿用同一套規則，
// 避免前端只對「當前這一頁」重排而把後端的順序打亂
const sortedMyBookings = computed(() =>
  [...myBookings.value].sort(
    (a, b) =>
      Number(a.status === 'CANCELLED') - Number(b.status === 'CANCELLED') ||
      new Date(a.startAt) - new Date(b.startAt),
  ),
)

function myBookingStatusInfo(status) {
  return MY_BOOKING_STATUS_MAP[status] || { label: status, className: '' }
}

// 轉成 FullCalendar 需要的 events 格式；額滿的場次換一套低調配色並在標題加註記，做視覺區分
const calendarEvents = computed(() =>
  roomFilteredSessions.value.map((session) => ({
    id: String(session.id),
    title: session.isFull
      ? `${session.courseName}｜${session.trainerName}（已額滿）`
      : `${session.courseName}｜${session.trainerName}`,
    start: session.startAt,
    end: session.endAt,
    backgroundColor: session.isFull ? 'var(--bg-soft)' : 'var(--accent)',
    borderColor: session.isFull ? 'var(--border)' : 'var(--accent-hover)',
    textColor: session.isFull ? 'var(--text-muted)' : '#fff',
    // 存整筆場次資料，點擊事件時不用再回頭用 id 查一次 sessions 陣列
    extendedProps: { session },
  })),
)

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

// 只取時間部分（HH:mm），詳情卡的結束時間不用重複顯示日期
function timeOnly(iso) {
  if (!iso) return ''
  const [, timePart] = iso.split('T')
  return (timePart || '').slice(0, 5)
}

const bookButtonLabel = computed(() => {
  if (!selectedSession.value) return ''
  if (selectedSession.value.isFull) return '已額滿'
  if (bookingSubmitting.value) return '報名中…'
  return '報名'
})

// 點擊行事曆上的事件色塊：開啟詳情卡，帶入這堂課的完整資料
function handleEventClick(info) {
  bookingError.value = ''
  selectedSession.value = info.event.extendedProps.session
  isDetailModalOpen.value = true
}

// 關閉／點遮罩／按 Esc 都走這裡；報名送出中先不讓使用者關掉，避免請求結果跟畫面對不上
function closeDetailModal() {
  if (bookingSubmitting.value) return
  isDetailModalOpen.value = false
  selectedSession.value = null
  bookingError.value = ''
}

// 點擊「報名」：呼叫 bookSession，成功關閉 modal、alert 提示、重新整頁刷新行事曆；
// 失敗則就地顯示在 modal 裡（系統層級錯誤改走全域提示條），維持 modal 開啟，
// 讓使用者看清楚訊息後自行決定要不要重試
async function handleBookInModal() {
  if (!selectedSession.value) return

  bookingError.value = ''
  bookingSubmitting.value = true
  try {
    await bookSession(selectedSession.value.id)
    // 先解除 loading 狀態，closeDetailModal() 才不會被自己的送出中防呆擋住
    bookingSubmitting.value = false
    closeDetailModal()
    showFeedback('報名成功')
    // 行事曆要更新剩餘名額，「我的團課報名」要出現這筆新紀錄——兩邊都得重抓，
    // 只抓其中一邊會讓使用者以為報名沒成功（寫法比照 handleCancelBooking）
    await Promise.all([fetchSessions(), fetchMyBookings()])
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      bookingError.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    bookingSubmitting.value = false
  }
}

function handleKeydown(e) {
  if (e.key === 'Escape' && isDetailModalOpen.value) {
    closeDetailModal()
  }
}

const calendarOptions = computed(() => ({
  plugins: [timeGridPlugin, dayGridPlugin],
  initialView: 'timeGridWeek',
  locale: zhTwLocale,
  headerToolbar: {
    left: 'prev,next today',
    center: 'title',
    right: '',
  },
  allDaySlot: false,
  height: 'auto',
  events: calendarEvents.value,
  eventClick: handleEventClick,
}))

async function fetchSessions() {
  loading.value = true
  try {
    const res = await getUpcomingSessions()
    sessions.value = res.data
  } finally {
    loading.value = false
  }
}

async function fetchMyBookings() {
  myBookingsLoading.value = true
  try {
    const res = await getMyBookings({ page: page.value, size: size.value, search: search.value })
    myBookings.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    myBookingsLoading.value = false
  }
}

// 搜尋字串變動時 debounce 400ms 才觸發查詢；換了搜尋條件代表結果集變了，
// page 一律重設為 0，避免卡在一個搜尋後根本不存在的頁碼（寫法比照 AdminGroupCourseView.vue）
watch(search, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchMyBookings()
  }, 400)
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchMyBookings()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchMyBookings()
}

// 點擊「取消」：先用瀏覽器原生 confirm() 做二次確認，確定才送出請求；成功後同時刷新
// 我的報名列表與行事曆（該場次的已報名人數要跟著變動），失敗則就地顯示錯誤訊息、
// 不刷新任何資料，讓使用者看清楚訊息後自行決定要不要重試
async function handleCancelBooking(booking) {
  const confirmed = window.confirm(`確定要取消「${booking.courseName}」這堂課的報名嗎？`)
  if (!confirmed) return

  myBookingsMessage.value = ''
  cancellingBookingId.value = booking.id
  try {
    await cancelBooking(booking.id)
    await Promise.all([fetchMyBookings(), fetchSessions()])
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      myBookingsMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    cancellingBookingId.value = null
  }
}

const auth = useAuthStore()

onMounted(() => {
  fetchSessions()
  // 「我的團課報名」需要 MEMBER 身分；未登入時不呼叫——
  // 否則會拿到 401，被 axios 攔截器當成登入過期而導向登入頁，
  // 使得這個本該公開的課表頁未登入者根本進不來。
  if (auth.isMember) {
    fetchMyBookings()
  }
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
  clearTimeout(searchDebounceTimer)
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">RESERVATION</span>
    <h2 class="section-title">團體課程</h2>
    <p class="section-desc">依教室篩選，以週曆檢視瀏覽即將開課的團體課程場次。</p>

    <!--
      行事曆必須「永遠掛載」，不能用 v-if/v-else 換掉：
      場次資料是由 FullCalendar 的 datesSet 事件觸發載入的，一旦它被移除，
      事件就不會再發生——載入中會卡在轉圈圈、切到沒課的那週則再也切不回來。
      因此 loading 與「本週沒課」都只作為疊加提示，不影響行事曆本身的存在。
    -->
    <div class="room-tabs">
      <button
        type="button"
        class="room-tab"
        :class="{ 'room-tab-active': selectedRoom === 'all' }"
        @click="selectedRoom = 'all'"
      >
        所有教室
      </button>
      <button
        v-for="room in rooms"
        :key="room"
        type="button"
        class="room-tab"
        :class="{ 'room-tab-active': selectedRoom === room }"
        @click="selectedRoom = room"
      >
        {{ room }}
      </button>
    </div>

    <div class="calendar-wrap">
      <div v-if="loading" class="calendar-notice"><div class="spinner"></div></div>
      <p v-else-if="visibleSessions.length === 0" class="empty-state calendar-notice">
        這一週沒有開放報名的團課場次，可切換到其他週次查看。
      </p>
      <FullCalendar :options="calendarOptions" />
    </div>

    <!-- 未登入時整塊隱藏：沒有資料來源，顯示空區塊反而困惑 -->
    <div v-if="auth.isMember" class="my-bookings-section">
      <h3 class="schedule-title">我的團課報名</h3>

      <div class="search-field">
        <input id="myBookingsSearch" v-model="search" type="text" placeholder="搜尋課程名稱或教室" />
      </div>

      <OperationMessage :message="myBookingsMessage" />

      <div v-if="myBookingsLoading" class="spinner"></div>

      <template v-else>
        <p v-if="sortedMyBookings.length === 0" class="empty-state">目前沒有團課報名紀錄</p>

        <table v-else class="slot-table">
          <thead>
            <tr>
              <th>課程名稱</th>
              <th>教室</th>
              <th>開始時間</th>
              <th>結束時間</th>
              <th>狀態</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="booking in sortedMyBookings" :key="booking.id">
              <td>{{ booking.courseName }}</td>
              <td>{{ booking.roomName }}</td>
              <td>{{ formatDateTime(booking.startAt) }}</td>
              <td>{{ formatDateTime(booking.endAt) }}</td>
              <td>
                <span class="status-badge" :class="myBookingStatusInfo(booking.status).className">
                  {{ myBookingStatusInfo(booking.status).label }}
                </span>
              </td>
              <td>
                <button
                  v-if="booking.status === 'BOOKED'"
                  class="btn btn-outline"
                  type="button"
                  :disabled="cancellingBookingId === booking.id"
                  @click="handleCancelBooking(booking)"
                >
                  {{ cancellingBookingId === booking.id ? '取消中…' : '取消' }}
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

    <div v-if="isDetailModalOpen" class="modal-overlay" @click.self="closeDetailModal">
      <div class="modal-content">
        <h3 class="modal-title">{{ selectedSession.courseName }}</h3>
        <div class="session-detail-list">
          <p class="session-detail session-detail-datetime">
            {{ formatDateTime(selectedSession.startAt) }}~{{ timeOnly(selectedSession.endAt) }}
          </p>
          <p class="session-detail">教練：{{ selectedSession.trainerName }}</p>
          <p class="session-detail">教室：{{ selectedSession.roomName }}</p>
          <p class="session-detail">已報名 {{ selectedSession.bookedCount }}/{{ selectedSession.capacity }} 人</p>
        </div>
        <OperationMessage :message="bookingError" />
        <div class="modal-actions">
          <button
            class="btn btn-accent"
            type="button"
            :disabled="selectedSession.isFull || bookingSubmitting"
            @click="handleBookInModal"
          >
            {{ bookButtonLabel }}
          </button>
          <button class="btn btn-outline" type="button" :disabled="bookingSubmitting" @click="closeDetailModal">
            關閉
          </button>
        </div>
      </div>
    </div>
    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.room-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1.25rem;
}

.room-tab {
  padding: 0.5rem 1.1rem;
  border: 1.5px solid var(--border);
  border-radius: 999px;
  background: var(--bg-section);
  color: var(--text-secondary);
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
  transition: var(--transition);
}

.room-tab:hover {
  border-color: var(--accent);
}

.room-tab-active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
  font-weight: 700;
}

.calendar-notice {
  /* 提示疊在行事曆上方，行事曆本身保持掛載——datesSet 是資料載入的觸發點，不能讓它被移除 */
  margin-bottom: 0.75rem;
  text-align: center;
}

.calendar-wrap {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1rem;
}

.empty-state {
  color: var(--text-muted);
  padding: 2rem 0;
}

/* FullCalendar 自己渲染的節點不吃 Vue 的 scoped 屬性，改用 :deep() 覆寫少數色彩變數，
   讓行事曆的邊框／今天背景／按鈕配色跟站台既有色系一致，不用整套重新刻樣式 */
.calendar-wrap :deep(.fc) {
  --fc-border-color: var(--border);
  --fc-today-bg-color: var(--bg-soft);
  --fc-neutral-bg-color: var(--bg-soft);
  --fc-page-bg-color: var(--bg-section);
  --fc-button-bg-color: var(--accent);
  --fc-button-border-color: var(--accent);
  --fc-button-hover-bg-color: var(--accent-hover);
  --fc-button-hover-border-color: var(--accent-hover);
  --fc-button-active-bg-color: var(--accent-hover);
  --fc-button-active-border-color: var(--accent-hover);
  font-family: inherit;
}

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 100;
}

.modal-content {
  width: 100%;
  max-width: 480px;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-hover);
  padding: 1.75rem;
}

.modal-title {
  margin: 0 0 1.25rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}

.session-detail-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.session-detail {
  margin: 0;
  font-size: 0.9rem;
  color: var(--text-secondary);
}

/* 日期時間是使用者決定要不要報名時最先看的資訊，字級跟顏色都比其他欄位更突出 */
.session-detail-datetime {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--accent-hover);
  margin-bottom: 0.25rem;
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}

.my-bookings-section {
  margin-top: 2.5rem;
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

.status-booked {
  background: #e8f3e6;
  color: #3f7a3a;
}

.status-cancelled {
  background: #f1ecea;
  color: var(--text-muted);
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
