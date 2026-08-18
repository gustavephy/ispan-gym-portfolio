<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { getAllCourses } from '@/api/adminGroupCourse.js'
import { getUpcomingSessions } from '@/api/groupClass.js'
import { createSession, cancelSession } from '@/api/adminGroupClass.js'

// 場次狀態對照表：後端回傳 SCHEDULED/CANCELLED，畫面顯示中文與顏色
const STATUS_MAP = {
  SCHEDULED: { label: '已排定', className: 'status-available' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

const courses = ref([])
const coursesLoading = ref(true)

const sessions = ref([])
const sessionsLoading = ref(true)

const form = ref({
  groupCourseId: '',
  roomName: '',
  startDate: '',
  startTime: '',
  endDate: '',
  endTime: '',
  capacity: '',
})
const formError = ref('')
const submitting = ref(false)

// 開始日期改到比目前結束日期更晚時，原本選的結束日期就不合法了，清空讓使用者重選
// （寫法比照 TrainerScheduleView.vue 的同名 watch）
watch(
  () => form.value.startDate,
  (newStartDate) => {
    if (newStartDate && form.value.endDate && form.value.endDate < newStartDate) {
      form.value.endDate = ''
    }
  },
)

// 24 小時、每 10 分鐘一個選項，寫法比照 TrainerScheduleView.vue
const TIME_OPTIONS = Array.from({ length: 24 * 6 }, (_, i) => {
  const totalMinutes = i * 10
  const hh = String(Math.floor(totalMinutes / 60)).padStart(2, '0')
  const mm = String(totalMinutes % 60).padStart(2, '0')
  return `${hh}:${mm}`
})

// 依開始時間排序；status 為 CANCELLED 的場次理論上後端不會回傳，這裡仍加一層防呆篩選
const visibleSessions = computed(() =>
  [...sessions.value]
    .filter((session) => session.status !== 'CANCELLED')
    .sort((a, b) => new Date(a.startAt) - new Date(b.startAt)),
)

function statusInfo(status) {
  return STATUS_MAP[status] || { label: status, className: '' }
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

// 今天的日期字串（YYYY-MM-DD），給「開始日期」欄位的 :min 用。
// 故意不用 new Date().toISOString().split('T')[0]：那個寫法會先轉成 UTC 再切字串，
// 在台灣時區（UTC+8）會有跨日誤差，晚上 8 點後執行會少算一天。
// getFullYear()/getMonth()/getDate() 回傳的都是瀏覽器所在時區的值，才是正確的「今天」。
// （寫法比照 TrainerScheduleView.vue 的同名函式）
function todayDateString() {
  const now = new Date()
  const yyyy = now.getFullYear()
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  const dd = String(now.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}

async function fetchCourses() {
  coursesLoading.value = true
  try {
    const res = await getAllCourses()
    courses.value = res.data
  } finally {
    coursesLoading.value = false
  }
}

async function fetchSessions() {
  sessionsLoading.value = true
  try {
    const res = await getUpcomingSessions()
    sessions.value = res.data
  } finally {
    sessionsLoading.value = false
  }
}

async function handleCreate() {
  formError.value = ''

  const { groupCourseId, roomName, startDate, startTime, endDate, endTime, capacity } = form.value
  if (!groupCourseId || !roomName || !startDate || !startTime || !endDate || !endTime) {
    formError.value = '請填寫課程、教室與開始/結束時間'
    return
  }

  // 日期跟時間兩個欄位分開收，這裡再組回後端要的 LocalDateTime 格式
  const startAt = `${startDate}T${startTime}:00`
  const endAt = `${endDate}T${endTime}:00`

  if (new Date(endAt) <= new Date(startAt)) {
    formError.value = '結束時間必須晚於開始時間'
    return
  }

  submitting.value = true
  try {
    await createSession({
      groupCourseId,
      roomName,
      startAt,
      endAt,
      capacity: capacity || null,
    })
    form.value = {
      groupCourseId: '',
      roomName: '',
      startDate: '',
      startTime: '',
      endDate: '',
      endTime: '',
      capacity: '',
    }
    await fetchSessions()
  } catch (err) {
    alert(err.response?.data?.message || '建立場次失敗，請稍後再試')
  } finally {
    submitting.value = false
  }
}

// 呼叫取消 API；若後端回 CONFIRMATION_REQUIRED（場次已有會員報名），跳出瀏覽器原生確認視窗，
// 使用者按確定就帶著 acknowledgeExistingBooking: true 重送一次，按取消則不重送，
// 讓例外往外拋出，維持該場次原狀（寫法比照 TrainerScheduleView 的 submitCancel）
async function submitCancelSession(sessionId, acknowledgeExistingBooking = false) {
  try {
    await cancelSession(sessionId, acknowledgeExistingBooking)
  } catch (err) {
    const { code, message } = err.response?.data || {}
    if (code === 'CONFIRMATION_REQUIRED' && !acknowledgeExistingBooking && window.confirm(message)) {
      await submitCancelSession(sessionId, true)
      return
    }
    throw err
  }
}

async function handleCancelSession(session) {
  try {
    await submitCancelSession(session.id)
    await fetchSessions()
  } catch (err) {
    alert(err.response?.data?.message || '取消場次失敗，請稍後再試')
  }
}

onMounted(() => {
  fetchCourses()
  fetchSessions()
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">團課場次排定</h2>
    <p class="section-desc">為團課主檔排定教室與時間，並查看目前所有場次的狀態。</p>

    <form class="slot-form" @submit.prevent="handleCreate">
      <div class="field">
        <label for="sessionCourse">課程</label>
        <select id="sessionCourse" v-model="form.groupCourseId" required :disabled="coursesLoading">
          <option value="" disabled>請選擇</option>
          <option v-for="course in courses" :key="course.id" :value="course.id">
            {{ course.name }}｜{{ course.trainerName }}
          </option>
        </select>
      </div>
      <div class="field">
        <label for="sessionRoom">教室</label>
        <input id="sessionRoom" v-model="form.roomName" type="text" required />
      </div>
      <div class="field">
        <label for="sessionStartDate">開始日期</label>
        <input id="sessionStartDate" v-model="form.startDate" type="date" :min="todayDateString()" required />
      </div>
      <div class="field">
        <label for="sessionStartTime">開始時間</label>
        <select id="sessionStartTime" v-model="form.startTime" required>
          <option value="" disabled>請選擇</option>
          <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="field">
        <label for="sessionEndDate">結束日期</label>
        <input id="sessionEndDate" v-model="form.endDate" type="date" :min="form.startDate" required />
      </div>
      <div class="field">
        <label for="sessionEndTime">結束時間</label>
        <select id="sessionEndTime" v-model="form.endTime" required>
          <option value="" disabled>請選擇</option>
          <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="field">
        <label for="sessionCapacity">容納人數</label>
        <input
          id="sessionCapacity"
          v-model.number="form.capacity"
          type="number"
          min="1"
          placeholder="留空則使用課程預設人數上限"
        />
      </div>
      <button class="btn btn-accent" type="submit" :disabled="submitting">
        {{ submitting ? '建立中…' : '建立場次' }}
      </button>
    </form>
    <p v-if="formError" class="form-error">{{ formError }}</p>

    <div v-if="sessionsLoading" class="spinner"></div>

    <template v-else>
      <p v-if="visibleSessions.length === 0" class="empty-state">目前還沒有任何團課場次，先建立第一筆看看。</p>

      <table v-else class="slot-table">
        <thead>
          <tr>
            <th>課程名稱</th>
            <th>教練</th>
            <th>教室</th>
            <th>開始時間</th>
            <th>結束時間</th>
            <th>已報名/容量</th>
            <th>狀態</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="session in visibleSessions" :key="session.id">
            <td>{{ session.courseName }}</td>
            <td>{{ session.trainerName }}</td>
            <td>{{ session.roomName }}</td>
            <td>{{ formatDateTime(session.startAt) }}</td>
            <td>{{ formatDateTime(session.endAt) }}</td>
            <td>{{ session.bookedCount }}/{{ session.capacity }}</td>
            <td>
              <span class="status-badge" :class="statusInfo(session.status).className">
                {{ statusInfo(session.status).label }}
              </span>
            </td>
            <td>
              <button
                v-if="session.status !== 'CANCELLED'"
                class="btn btn-outline"
                type="button"
                @click="handleCancelSession(session)"
              >
                取消
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.slot-form {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 1rem;
  margin-bottom: 0.75rem;
  padding: 1.5rem;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.field label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.field input,
.field select {
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
}

.form-error {
  color: #b3452f;
  font-size: 0.9rem;
  margin-bottom: 1.5rem;
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

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
}

.status-available {
  background: #e8f3e6;
  color: #3f7a3a;
}

.status-cancelled {
  background: #f1ecea;
  color: var(--text-muted);
}
</style>
