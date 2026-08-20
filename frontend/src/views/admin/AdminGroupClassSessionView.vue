<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { getActiveCourses } from '@/api/groupClass.js'
import { getSessions, createSession, updateSession, cancelSession } from '@/api/adminGroupClass.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'
import { TIME_OPTIONS } from '@/utils/businessHours.js'

// 場次狀態對照表：後端回傳 SCHEDULED/CANCELLED，畫面顯示中文與顏色
const STATUS_MAP = {
  SCHEDULED: { label: '已排定', className: 'status-available' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

// 團課教室清單：目前只有這 4 間，對應現有 3 種團課類型（瑜珈/拳擊/重量訓練）
// 加一間測試用教室。之後要新增教室，只需要在這個陣列加一筆，不用動後端或資料庫
// ——roomName 在後端只是純字串欄位，沒有教室主檔表（見專案技術債討論，暫不建 Room
// entity，避免期末專題過度設計）。
const ROOM_OPTIONS = ['瑜珈教室', '拳擊教室', '團體重訓室', '測試用教室A']

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

// 取消場次操作的就地錯誤訊息，顯示在場次列表上方
const sessionListMessage = ref('')

// 場次列表的分頁與搜尋狀態
const page = ref(0)
const size = ref(10)
const search = ref('')
const totalItems = ref(0)
const totalPages = ref(0)
let searchDebounceTimer = null

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

// 編輯場次用的 modal 狀態，跟最上面的建立表單完全分開，互不影響
const isEditModalOpen = ref(false)
const editingSessionId = ref(null)
// 樂觀鎖版本，不顯示在畫面上，內部保留用於送出時一併帶回
const editingSessionVersion = ref(null)
const editForm = ref({ roomName: '', startDate: '', startTime: '', endDate: '', endTime: '', capacity: '' })
const editFormError = ref('')
const editSubmitting = ref(false)

watch(
  () => editForm.value.startDate,
  (newStartDate) => {
    if (newStartDate && editForm.value.endDate && editForm.value.endDate < newStartDate) {
      editForm.value.endDate = ''
    }
  },
)

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

// 把 ISO 格式的 LocalDateTime 字串（如 "2026-08-20T10:00:00"）拆回表單用的日期＋時間下拉選單格式
// 用字串切割而非 new Date().toLocaleXxx()，避免經過 Date 物件轉換時受瀏覽器時區影響
// （寫法比照 TrainerScheduleView.vue 的同名函式）
function splitIsoDateTime(iso) {
  const [datePart, timePart] = iso.split('T')
  return { date: datePart, time: (timePart || '').slice(0, 5) }
}

async function fetchCourses() {
  coursesLoading.value = true
  try {
    // size 給大一點：這是下拉選單用途，不是分頁列表，一次抓齊所有上架中課程即可。
    // 目前後端這支查詢單頁上限是 100（GroupCourseService 的 safeSize 防呆），
    // 正常情況下健身房的上架課程數不會超過這個量級；真的超過再考慮做成可搜尋的下拉選單。
    const res = await getActiveCourses({ size: 100 })
    courses.value = res.data.items
  } finally {
    coursesLoading.value = false
  }
}

async function fetchSessions() {
  sessionsLoading.value = true
  try {
    const res = await getSessions({ page: page.value, size: size.value, search: search.value })
    sessions.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    sessionsLoading.value = false
  }
}

// 搜尋字串變動時 debounce 400ms 才觸發查詢；換了搜尋條件代表結果集變了，
// page 一律重設為 0，避免卡在一個搜尋後根本不存在的頁碼（寫法比照 AdminGroupCourseView.vue）
watch(search, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchSessions()
  }, 400)
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchSessions()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchSessions()
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

  const durationMinutes = (new Date(endAt) - new Date(startAt)) / 60000
  if (durationMinutes < 60 || durationMinutes > 90) {
    formError.value = '場次長度需介於 60 分鐘至 90 分鐘之間'
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
    const apiError = err
    if (isInlineError(apiError)) {
      formError.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
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
    // 攔截器 reject 的是 normalizeApiError() 正規化後的物件（{code, message, needsConfirmation, raw…}），
    // 不是原始的 axios error——所以這裡不能讀 err.response.data，那永遠是 undefined，
    // 確認框就再也不會跳出來，使用者只會看到一行「請確認」的錯誤訊息卻無從確認。
    if (err.needsConfirmation && !acknowledgeExistingBooking && window.confirm(err.message)) {
      await submitCancelSession(sessionId, true)
      return
    }
    throw err
  }
}

async function handleCancelSession(session) {
  sessionListMessage.value = ''
  try {
    await submitCancelSession(session.id)
    await fetchSessions()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      sessionListMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點擊「編輯」：開啟 modal，並把這筆場次既有的教室/時間/容量帶入編輯表單；
// version 一併記住（不顯示在畫面上），送出時要帶回給後端做樂觀鎖檢查
function openEditModal(session) {
  editFormError.value = ''
  const start = splitIsoDateTime(session.startAt)
  const end = splitIsoDateTime(session.endAt)
  editForm.value = {
    roomName: session.roomName,
    startDate: start.date,
    startTime: start.time,
    endDate: end.date,
    endTime: end.time,
    capacity: session.capacity,
  }
  editingSessionId.value = session.id
  editingSessionVersion.value = session.version
  isEditModalOpen.value = true
}

// 取消／點遮罩都走這裡：關閉 modal，不送出任何請求
function closeEditModal() {
  isEditModalOpen.value = false
  editingSessionId.value = null
  editingSessionVersion.value = null
  editForm.value = { roomName: '', startDate: '', startTime: '', endDate: '', endTime: '', capacity: '' }
  editFormError.value = ''
}

async function handleEditSubmit() {
  editFormError.value = ''

  const { roomName, startDate, startTime, endDate, endTime, capacity } = editForm.value
  if (!roomName || !startDate || !startTime || !endDate || !endTime) {
    editFormError.value = '請填寫教室與開始/結束時間'
    return
  }

  // 日期跟時間兩個欄位分開收，這裡再組回後端要的 LocalDateTime 格式
  const startAt = `${startDate}T${startTime}:00`
  const endAt = `${endDate}T${endTime}:00`

  if (new Date(endAt) <= new Date(startAt)) {
    editFormError.value = '結束時間必須晚於開始時間'
    return
  }

  const durationMinutes = (new Date(endAt) - new Date(startAt)) / 60000
  if (durationMinutes < 60 || durationMinutes > 90) {
    editFormError.value = '場次長度需介於 60 分鐘至 90 分鐘之間'
    return
  }

  editSubmitting.value = true
  try {
    await updateSession(editingSessionId.value, {
      roomName,
      startAt,
      endAt,
      capacity: capacity || null,
      version: editingSessionVersion.value,
    })
    closeEditModal()
    await fetchSessions()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      // 版本衝突（樂觀鎖）是預期中會發生的競態情況，不能自動重試（不知道使用者這次改的
      // 內容是否還適用於別人剛改過的新版本），只能請使用者自己重新整理列表、看清楚最新狀態再改一次
      editFormError.value =
        apiError.status === 409 && apiError.message.includes('已被修改')
          ? `${apiError.message}，請關閉後重新整理列表再試`
          : apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    editSubmitting.value = false
  }
}

onMounted(() => {
  fetchCourses()
  fetchSessions()
})

onUnmounted(() => {
  clearTimeout(searchDebounceTimer)
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
        <select id="sessionRoom" v-model="form.roomName" required>
          <option value="" disabled>請選擇</option>
          <option v-for="room in ROOM_OPTIONS" :key="room" :value="room">{{ room }}</option>
        </select>
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

    <div class="search-field">
      <input id="sessionSearch" v-model="search" type="text" placeholder="搜尋課程名稱、教練或教室" />
    </div>

    <OperationMessage :message="sessionListMessage" />

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
              <div v-if="session.status !== 'CANCELLED'" class="row-actions">
                <button class="btn btn-outline" type="button" @click="openEditModal(session)">編輯</button>
                <button class="btn btn-outline" type="button" @click="handleCancelSession(session)">取消</button>
              </div>
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

    <div v-if="isEditModalOpen" class="modal-overlay" @click.self="closeEditModal">
      <div class="modal-content">
        <h3 class="modal-title">編輯場次</h3>
        <form @submit.prevent="handleEditSubmit">
          <div class="modal-fields">
            <div class="field field-wide">
              <label for="editSessionRoom">教室</label>
              <select id="editSessionRoom" v-model="editForm.roomName" required>
                <option value="" disabled>請選擇</option>
                <option v-for="room in ROOM_OPTIONS" :key="room" :value="room">{{ room }}</option>
              </select>
            </div>
            <div class="field-row">
              <div class="field">
                <label for="editSessionStartDate">開始日期</label>
                <input
                  id="editSessionStartDate"
                  v-model="editForm.startDate"
                  type="date"
                  :min="todayDateString()"
                  required
                />
              </div>
              <div class="field">
                <label for="editSessionStartTime">開始時間</label>
                <select id="editSessionStartTime" v-model="editForm.startTime" required>
                  <option value="" disabled>請選擇</option>
                  <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
                </select>
              </div>
            </div>
            <div class="field-row">
              <div class="field">
                <label for="editSessionEndDate">結束日期</label>
                <input
                  id="editSessionEndDate"
                  v-model="editForm.endDate"
                  type="date"
                  :min="editForm.startDate"
                  required
                />
              </div>
              <div class="field">
                <label for="editSessionEndTime">結束時間</label>
                <select id="editSessionEndTime" v-model="editForm.endTime" required>
                  <option value="" disabled>請選擇</option>
                  <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
                </select>
              </div>
            </div>
            <div class="field">
              <label for="editSessionCapacity">容納人數</label>
              <input id="editSessionCapacity" v-model.number="editForm.capacity" type="number" min="1" />
            </div>
          </div>
          <p v-if="editFormError" class="form-error">{{ editFormError }}</p>
          <div class="modal-actions">
            <button class="btn btn-accent" type="submit" :disabled="editSubmitting">
              {{ editSubmitting ? '儲存中…' : '儲存修改' }}
            </button>
            <button class="btn btn-outline" type="button" :disabled="editSubmitting" @click="closeEditModal">
              取消
            </button>
          </div>
        </form>
      </div>
    </div>
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

.row-actions {
  display: flex;
  gap: 0.5rem;
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

.modal-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
}

/* 開始/結束各自成一排，日期與時間並排：用明確的 row 容器取代單純 flex-wrap，
   避免欄位寬度不一時被排版擠到不相關的欄位同一排（如開始時間跟結束日期擠在一起） */
.field-row {
  display: flex;
  gap: 1rem;
  flex: 1 1 100%;
}

.field-row .field {
  flex: 1;
}

.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}
</style>
