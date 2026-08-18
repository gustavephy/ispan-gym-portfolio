<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { getMySlots, createSlot, updateSlot, cancelSlot, checkInBooking } from '@/api/coachSchedule.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'
import { useFeedback } from '@/composables/useFeedback.js'
import Pagination from '@/components/Pagination.vue'

// 成功提示用 toast，不用 window.alert（會阻斷畫面且要多按一次確定）
const { feedback, showFeedback } = useFeedback(2500)

// 狀態對照表：後端回傳 AVAILABLE/BOOKED/CANCELLED，畫面顯示中文與顏色
const STATUS_MAP = {
  AVAILABLE: { label: '可預約', className: 'status-available' },
  BOOKED: { label: '已被預約', className: 'status-booked' },
  CANCELLED: { label: '已取消', className: 'status-cancelled' },
}

const slots = ref([])
const loading = ref(true)
const submitting = ref(false)

const form = ref({ startDate: '', startTime: '', endDate: '', endTime: '' })
const formError = ref('')

// 取消／核銷這兩個列表按鈕操作共用同一個就地訊息，顯示在表格上方（這兩個操作本來就沒有 modal）
const slotActionMessage = ref('')

// 分頁狀態；這頁沒有搜尋需求
const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

// 開始日期改到比目前結束日期更晚時，原本選的結束日期就不合法了，清空讓使用者重選
watch(
  () => form.value.startDate,
  (newStartDate) => {
    if (newStartDate && form.value.endDate && form.value.endDate < newStartDate) {
      form.value.endDate = ''
    }
  },
)

// 編輯時段用的 modal 狀態，跟最上面的建立表單完全分開，互不影響
const isEditModalOpen = ref(false)
const editingSlotId = ref(null)
const editForm = ref({ startDate: '', startTime: '', endDate: '', endTime: '' })
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

// 24 小時、每 10 分鐘一個選項，自己產生選單內容，不依賴瀏覽器對 <input type="time"> step 的支援
// （不同瀏覽器版本表現不一致，直接用 <select> 才能保證選項一定是 10 分鐘一格）
const TIME_OPTIONS = Array.from({ length: 24 * 6 }, (_, i) => {
  const totalMinutes = i * 10
  const hh = String(Math.floor(totalMinutes / 60)).padStart(2, '0')
  const mm = String(totalMinutes % 60).padStart(2, '0')
  return `${hh}:${mm}`
})

function statusInfo(status) {
  return STATUS_MAP[status] || { label: status, className: '' }
}

// BOOKED 時段要進一步依 bookingStatus 區分「待核銷」跟「已完成」，
// 其他狀態（AVAILABLE／CANCELLED）沒有 bookingStatus 可看，直接沿用 statusInfo 的對照表
function slotStatusInfo(slot) {
  if (slot.status === 'BOOKED' && slot.bookingStatus === 'PENDING') {
    return { label: '已預約（待核銷）', className: 'status-booked' }
  }
  if (slot.status === 'BOOKED' && slot.bookingStatus === 'COMPLETED') {
    return { label: '已完成', className: 'status-completed' }
  }
  return statusInfo(slot.status)
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

async function fetchSlots() {
  loading.value = true
  try {
    const res = await getMySlots({ page: page.value, size: size.value })
    // 已信任後端回傳的排序，不再於前端重新 sort 一次
    slots.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    loading.value = false
  }
}

function handlePageChange(newPage) {
  page.value = newPage
  fetchSlots()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchSlots()
}

// 今天的日期字串（YYYY-MM-DD），給「開始日期」欄位的 :min 用。
// 故意不用 new Date().toISOString().split('T')[0]：那個寫法會先轉成 UTC 再切字串，
// 在台灣時區（UTC+8）會有跨日誤差，晚上 8 點後執行會少算一天。
// getFullYear()/getMonth()/getDate() 回傳的都是瀏覽器所在時區的值，才是正確的「今天」。
function todayDateString() {
  const now = new Date()
  const yyyy = now.getFullYear()
  const mm = String(now.getMonth() + 1).padStart(2, '0')
  const dd = String(now.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}

// 把 ISO 格式的 LocalDateTime 字串（如 "2026-08-20T10:00:00"）拆回表單用的日期＋時間下拉選單格式
// 用字串切割而非 new Date().toLocaleXxx()，避免經過 Date 物件轉換時受瀏覽器時區影響
function splitIsoDateTime(iso) {
  const [datePart, timePart] = iso.split('T')
  return { date: datePart, time: (timePart || '').slice(0, 5) }
}

// 建立表單與編輯 modal 共用同一套驗證規則，回傳 { error } 或組好的 { startAt, endAt }
function buildAndValidate({ startDate, startTime, endDate, endTime }) {
  if (!startDate || !startTime || !endDate || !endTime) {
    return { error: '請填寫開始與結束的日期和時間' }
  }
  // 日期跟時間兩個欄位分開收，這裡再組回後端要的 LocalDateTime 格式
  const startAt = `${startDate}T${startTime}:00`
  const endAt = `${endDate}T${endTime}:00`

  if (new Date(endAt) <= new Date(startAt)) {
    return { error: '結束時間必須晚於開始時間' }
  }
  // 後端 DTO 的 @Future 會擋過去時間，但那要送出去才知道；先在這裡擋，
  // 使用者不必等一次往返才看到錯誤（同一條規則兩邊都有，前端只是提早回饋）
  if (new Date(startAt) <= new Date()) {
    return { error: '開始時間必須是未來時間' }
  }
  const durationMinutes = (new Date(endAt) - new Date(startAt)) / 60000
  if (durationMinutes < 60 || durationMinutes > 90) {
    return { error: '時段長度需介於 60 分鐘至 90 分鐘之間' }
  }
  return { startAt, endAt }
}

async function handleCreate() {
  formError.value = ''

  const result = buildAndValidate(form.value)
  if (result.error) {
    formError.value = result.error
    return
  }

  submitting.value = true
  try {
    await createSlot({ startAt: result.startAt, endAt: result.endAt })
    form.value = { startDate: '', startTime: '', endDate: '', endTime: '' }
    await fetchSlots()
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

// 點擊「編輯」：開啟 modal，並把這筆時段既有的時間帶入編輯表單
function openEditModal(slot) {
  editFormError.value = ''
  const start = splitIsoDateTime(slot.startAt)
  const end = splitIsoDateTime(slot.endAt)
  editForm.value = { startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time }
  editingSlotId.value = slot.id
  isEditModalOpen.value = true
}

// 取消／點遮罩／按 Esc 都走這裡：關閉 modal，不送出任何請求
function closeEditModal() {
  isEditModalOpen.value = false
  editingSlotId.value = null
  editForm.value = { startDate: '', startTime: '', endDate: '', endTime: '' }
  editFormError.value = ''
}

// 呼叫修改 API；若後端回 CONFIRMATION_REQUIRED（時段已被預約），跳出瀏覽器原生確認視窗，
// 使用者按確定就帶著 acknowledgeExistingBooking: true 重送一次，按取消則不重送，
// 讓例外往外拋出，留在 modal 裡顯示訊息給使用者自己決定要不要修改內容再送
async function submitUpdate(slotId, startAt, endAt, acknowledgeExistingBooking = false) {
  try {
    await updateSlot(slotId, { startAt, endAt, acknowledgeExistingBooking })
  } catch (err) {
    // 攔截器 reject 的是 normalizeApiError() 正規化後的物件（{code, message, needsConfirmation, raw…}），
    // 不是原始 axios error——讀 err.response.data 永遠是 undefined，確認框就再也不會跳，
    // 使用者只會看到一行「請確認」卻無從確認
    if (err.needsConfirmation && !acknowledgeExistingBooking && window.confirm(err.message)) {
      await submitUpdate(slotId, startAt, endAt, true)
      return
    }
    throw err
  }
}

async function handleEditSubmit() {
  editFormError.value = ''

  const result = buildAndValidate(editForm.value)
  if (result.error) {
    editFormError.value = result.error
    return
  }

  editSubmitting.value = true
  try {
    await submitUpdate(editingSlotId.value, result.startAt, result.endAt)
    closeEditModal()
    await fetchSlots()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      editFormError.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  } finally {
    editSubmitting.value = false
  }
}

// 呼叫取消 API；若後端回 CONFIRMATION_REQUIRED（時段已被預約，取消會連鎖退還會員堂數），
// 跳出瀏覽器原生確認視窗，使用者按確定就帶著 acknowledgeExistingBooking: true 重送一次，
// 按取消則不重送，讓例外往外拋出，維持該時段原狀（寫法比照 submitUpdate）
async function submitCancel(slotId, acknowledgeExistingBooking = false) {
  try {
    await cancelSlot(slotId, acknowledgeExistingBooking)
  } catch (err) {
    // 同上：讀正規化後的 needsConfirmation，不是 err.response.data
    if (err.needsConfirmation && !acknowledgeExistingBooking && window.confirm(err.message)) {
      await submitCancel(slotId, true)
      return
    }
    throw err
  }
}

// 點擊「取消」：先做第一層確認，使用者按確定才呼叫取消 API
async function handleCancel(slot) {
  if (!window.confirm('確定要取消這個時段嗎？')) return

  slotActionMessage.value = ''
  try {
    await submitCancel(slot.id)
    await fetchSlots()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      slotActionMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點擊「核銷」：用 prompt 讓教練輸入會員出示的核銷碼，取消或空字串都不送請求
async function handleCheckIn(slot) {
  const checkInKey = window.prompt('請輸入會員出示的核銷碼：')
  if (!checkInKey) return

  slotActionMessage.value = ''
  try {
    await checkInBooking(slot.bookingId, checkInKey)
    showFeedback('核銷成功')
    await fetchSlots()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      slotActionMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

function handleKeydown(e) {
  if (e.key === 'Escape' && isEditModalOpen.value) {
    closeEditModal()
  }
}

onMounted(() => {
  fetchSlots()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">TRAINER</span>
    <h2 class="section-title">我的排班</h2>
    <p class="section-desc">建立可預約時段，並查看目前所有時段的狀態。</p>

    <form class="slot-form" @submit.prevent="handleCreate">
      <div class="field">
        <label for="startDate">開始日期</label>
        <input id="startDate" v-model="form.startDate" type="date" :min="todayDateString()" required />
      </div>
      <div class="field">
        <label for="startTime">開始時間</label>
        <select id="startTime" v-model="form.startTime" required>
          <option value="" disabled>請選擇</option>
          <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="field">
        <label for="endDate">結束日期</label>
        <input id="endDate" v-model="form.endDate" type="date" :min="form.startDate" required />
      </div>
      <div class="field">
        <label for="endTime">結束時間</label>
        <select id="endTime" v-model="form.endTime" required>
          <option value="" disabled>請選擇</option>
          <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <button class="btn btn-accent" type="submit" :disabled="submitting">
        {{ submitting ? '建立中…' : '建立時段' }}
      </button>
    </form>
    <p v-if="formError" class="form-error">{{ formError }}</p>

    <OperationMessage :message="slotActionMessage" />

    <div v-if="loading" class="spinner"></div>

    <template v-else>
      <p v-if="slots.length === 0" class="empty-state">目前還沒有任何時段，先建立第一筆看看。</p>

      <table v-else class="slot-table">
        <thead>
          <tr>
            <th>開始時間</th>
            <th>結束時間</th>
            <th>狀態</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="slot in slots" :key="slot.id">
            <td>{{ formatDateTime(slot.startAt) }}</td>
            <td>{{ formatDateTime(slot.endAt) }}</td>
            <td>
              <span class="status-badge" :class="slotStatusInfo(slot).className">
                {{ slotStatusInfo(slot).label }}
              </span>
            </td>
            <td>
              <div v-if="slot.status !== 'CANCELLED'" class="row-actions">
                <button class="btn btn-outline" type="button" @click="openEditModal(slot)">編輯</button>
                <button class="btn btn-outline" type="button" @click="handleCancel(slot)">取消</button>
                <button v-if="slot.status === 'BOOKED' && slot.bookingStatus === 'PENDING'" class="btn btn-outline"
                  type="button" @click="handleCheckIn(slot)">
                  核銷
                </button>
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
        <h3 class="modal-title">編輯時段</h3>
        <form @submit.prevent="handleEditSubmit">
          <div class="modal-fields">
            <div class="field">
              <label for="editStartDate">開始日期</label>
              <input id="editStartDate" v-model="editForm.startDate" type="date" :min="todayDateString()" required />
            </div>
            <div class="field">
              <label for="editStartTime">開始時間</label>
              <select id="editStartTime" v-model="editForm.startTime" required>
                <option value="" disabled>請選擇</option>
                <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
              </select>
            </div>
            <div class="field">
              <label for="editEndDate">結束日期</label>
              <input id="editEndDate" v-model="editForm.endDate" type="date" :min="editForm.startDate" required />
            </div>
            <div class="field">
              <label for="editEndTime">結束時間</label>
              <select id="editEndTime" v-model="editForm.endTime" required>
                <option value="" disabled>請選擇</option>
                <option v-for="t in TIME_OPTIONS" :key="t" :value="t">{{ t }}</option>
              </select>
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

    <FeedbackToast :feedback="feedback" />
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

.row-actions {
  display: flex;
  gap: 0.5rem;
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

.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}
</style>
