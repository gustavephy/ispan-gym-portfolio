<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { getTrainers, getTrainerSchedules, createBooking } from '@/api/coachBooking.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'

const route = useRoute()
const trainerId = Number(route.params.trainerId)

const trainer = ref(null)
const trainerLoading = ref(true)

const slots = ref([])
const slotsLoading = ref(false)

// 時段列表的分頁與日期區間篩選狀態；這頁不開放狀態篩選，固定只查 AVAILABLE
const page = ref(0)
const size = ref(10)
const dateFrom = ref('')
const dateTo = ref('')
const totalItems = ref(0)
const totalPages = ref(0)

// 正在送出預約的時段 id；用來讓「預約」按鈕在等待回應期間顯示 loading、避免重複點擊
const bookingSlotId = ref(null)

// 預約的就地錯誤訊息，比照原本 ReservationView.vue 的 bookingActionMessage
const bookingActionMessage = ref('')

// 依開始時間排序，未來時段在前（跟原本 ReservationView.vue 的 sortedSlots 同樣的排序邏輯）
const sortedSlots = computed(() =>
  [...slots.value].sort((a, b) => new Date(a.startAt) - new Date(b.startAt)),
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

// 只取時間部分（HH:mm），給 confirm() 訊息裡的結束時間用，不用重複顯示日期
function timeOnly(iso) {
  const [, timePart] = iso.split('T')
  return (timePart || '').slice(0, 5)
}

// 時段開始時間是否已過去；頁面開著不會自動重抓資料，這裡只在渲染/點擊當下即時比對
function isPastSlot(slot) {
  return new Date(slot.startAt).getTime() < Date.now()
}

// 教練資訊沒有單獨查詢的後端端點，一律用既有的 getTrainers() 在前端過濾找出這一位；
// 只留 ACTIVE，跟原本 ReservationView.vue 的 fetchTrainers 是同一套慣例——
// 找不到（含教練已離職、或網址被亂改成不存在的 id）就顯示「查無此教練」
async function fetchTrainerInfo() {
  trainerLoading.value = true
  try {
    const res = await getTrainers()
    trainer.value = res.data.find((t) => t.status === 'ACTIVE' && t.trainerId === trainerId) ?? null
  } finally {
    trainerLoading.value = false
  }
}

async function fetchSlots() {
  slotsLoading.value = true
  try {
    const res = await getTrainerSchedules(trainerId, {
      page: page.value,
      size: size.value,
      dateFrom: dateFrom.value,
      dateTo: dateTo.value,
    })
    // 這個頁面只做瀏覽可預約時段，BOOKED／CANCELLED 的時段不在會員視角顯示
    slots.value = res.data.items.filter((slot) => slot.status === 'AVAILABLE')
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    slotsLoading.value = false
  }
}

// 日期輸入不像文字輸入會逐字觸發，不需要 debounce
watch([dateFrom, dateTo], () => {
  page.value = 0
  fetchSlots()
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchSlots()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchSlots()
}

// 點擊「預約」：先用瀏覽器原生 confirm() 做二次確認，讓使用者看清楚自己在約什麼堂課，
// 確定才送出請求；成功後把核銷碼顯示給使用者，並刷新這頁的時段列表讓已被約走的時段消失，
// 失敗則顯示錯誤訊息、維持列表原狀，讓使用者可以再試一次或選別的時段
async function handleBook(slot) {
  const trainerLabel = trainer.value?.displayName ?? ''
  const confirmed = window.confirm(
    `確定要預約 ${trainerLabel} ${formatDateTime(slot.startAt)}~${timeOnly(slot.endAt)} 這堂課嗎？`,
  )
  if (!confirmed) return

  bookingActionMessage.value = ''
  bookingSlotId.value = slot.id
  try {
    const res = await createBooking(slot.id)
    // 這裡刻意用 alert 而非 toast：核銷碼是使用者必須記下來的資訊，
    // 自動消失的 toast 有錯過的風險，阻斷式對話框逼使用者確實看過才繼續。
    alert(`預約成功！核銷碼：${res.data.checkInKey}，上課時請出示給教練核銷`)
    await fetchSlots()
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
  fetchTrainerInfo()
  fetchSlots()
})
</script>

<template>
  <div class="page-container">
    <RouterLink to="/reservation" class="back-link">← 返回選擇教練</RouterLink>

    <div v-if="trainerLoading" class="spinner"></div>

    <p v-else-if="!trainer" class="empty-state">查無此教練</p>

    <template v-else>
      <span class="section-tag">RESERVATION</span>
      <h2 class="section-title">{{ trainer.displayName }} 的可預約時段</h2>
      <p class="trainer-specialty">{{ trainer.specialty }}</p>
      <p class="trainer-experience">{{ trainer.experience }}</p>

      <div class="schedule-section">
        <h3 class="schedule-title">可預約時段</h3>

        <OperationMessage :message="bookingActionMessage" />

        <div class="filters">
          <label class="filter">
            <span class="filter__label">起始日期</span>
            <input v-model="dateFrom" type="date" class="filter__input" :max="dateTo || undefined" />
          </label>
          <label class="filter">
            <span class="filter__label">結束日期</span>
            <input v-model="dateTo" type="date" class="filter__input" :min="dateFrom || undefined" />
          </label>
        </div>

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
                    :disabled="bookingSlotId === slot.id || isPastSlot(slot)"
                    @click="handleBook(slot)"
                  >
                    {{ bookingSlotId === slot.id ? '預約中…' : isPastSlot(slot) ? '已截止' : '預約' }}
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
    </template>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 1.25rem;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-decoration: none;
}

.back-link:hover {
  color: var(--primary-dark);
}

.trainer-specialty {
  margin: 0.75rem 0 0.35rem;
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

.schedule-title {
  margin: 0 0 1rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 1.5rem;
  margin-bottom: 1rem;
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
