<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getUpcomingSessions, bookSession } from '@/api/groupClass.js'
import FullCalendar from '@fullcalendar/vue3'
import timeGridPlugin from '@fullcalendar/timegrid'
import dayGridPlugin from '@fullcalendar/daygrid'
import zhTwLocale from '@fullcalendar/core/locales/zh-tw'

const sessions = ref([])
const loading = ref(true)

// 目前選中的教室分頁；'all' 代表「所有教室」，純前端篩選，不重打 API
const selectedRoom = ref('all')

// 詳情卡 modal 狀態：點擊行事曆事件時開啟，selectedSession 存整筆場次資料供 modal 顯示與報名用
const isDetailModalOpen = ref(false)
const selectedSession = ref(null)
const bookingSubmitting = ref(false)

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
  selectedSession.value = info.event.extendedProps.session
  isDetailModalOpen.value = true
}

// 關閉／點遮罩／按 Esc 都走這裡；報名送出中先不讓使用者關掉，避免請求結果跟畫面對不上
function closeDetailModal() {
  if (bookingSubmitting.value) return
  isDetailModalOpen.value = false
  selectedSession.value = null
}

// 點擊「報名」：呼叫 bookSession，成功關閉 modal、alert 提示、重新整頁刷新行事曆；
// 失敗則 alert 顯示錯誤訊息、維持 modal 開啟，讓使用者看清楚訊息後自行決定要不要重試
async function handleBookInModal() {
  if (!selectedSession.value) return

  bookingSubmitting.value = true
  try {
    await bookSession(selectedSession.value.id)
    // 先解除 loading 狀態，closeDetailModal() 才不會被自己的送出中防呆擋住
    bookingSubmitting.value = false
    closeDetailModal()
    alert('報名成功')
    await fetchSessions()
  } catch (err) {
    alert(err.response?.data?.message || '報名失敗，請稍後再試')
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

onMounted(() => {
  fetchSessions()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">RESERVATION</span>
    <h2 class="section-title">團體課程</h2>
    <p class="section-desc">依教室篩選，以週曆檢視瀏覽即將開課的團體課程場次。</p>

    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="visibleSessions.length === 0" class="empty-state">目前沒有開放報名的團課場次。</p>

      <template v-else>
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
          <FullCalendar :options="calendarOptions" />
        </div>
      </template>
    </template>

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
</style>
