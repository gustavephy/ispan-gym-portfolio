<template>
  <div class="my-reservations-module">
    <!-- 篩選列 -->
    <div class="filter-panel card" style="margin-bottom: 2rem;">
      <div class="filter-row">
        <div class="filter-item">
          <label class="form-label">關鍵字</label>
          <div style="position: relative;">
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜尋課程名稱或教練..."
              class="form-input"
              style="padding-right: 2rem;"
            />
            <button v-if="searchQuery" @click="searchQuery = ''" class="clear-btn" type="button">&#x2715;</button>
          </div>
        </div>
        <div class="filter-item">
          <label class="form-label">指導教練</label>
          <BaseSelect
            v-model="selectedTrainerId"
            :options="[{ label: '全部教練', value: '' }, ...trainers.map(t => ({ label: t.name, value: String(t.id) }))]"
          />
        </div>
        <div class="filter-item">
          <label class="form-label">開始日期</label>
          <input
            v-model="startDate"
            type="date"
            class="form-input"
          />
        </div>
        <div class="filter-item">
          <label class="form-label">結束日期</label>
          <input
            v-model="endDate"
            type="date"
            class="form-input"
          />
        </div>
      </div>
    </div>

    <!-- 子分頁按鈕與課程類型 -->
    <div style="display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: 1.5rem; flex-wrap: wrap;">
      <div class="sub-tab-control" style="display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0;">
        <button type="button" :class="['sub-tab-btn', { active: subTab === 'all' }]" @click="subTab = 'all'">全部 ({{ reservations.length }})</button>
        <button type="button" :class="['sub-tab-btn', { active: subTab === 'upcoming' }]" @click="subTab = 'upcoming'">即將上課 ({{ upcomingCount }})</button>
        <button type="button" :class="['sub-tab-btn', { active: subTab === 'expired' }]" @click="subTab = 'expired'">已過期 ({{ expiredCount }})</button>
        <button type="button" :class="['sub-tab-btn', { active: subTab === 'completed' }]" @click="subTab = 'completed'">已上課 ({{ completedCount }})</button>
        <button type="button" :class="['sub-tab-btn', { active: subTab === 'cancelled' }]" @click="subTab = 'cancelled'">已取消 ({{ cancelledCount }})</button>
      </div>

      <div style="display: flex; align-items: center; gap: 0.5rem; flex-shrink: 0;">
        <span class="form-label" style="margin-bottom: 0; font-size: 0.85rem; flex-shrink: 0;">課程類型：</span>
        <div class="segmented-control" style="width: 220px; flex-shrink: 0;">
          <button type="button" :class="['seg-btn', { active: selectedType === '' }]" @click="selectedType = ''">全部</button>
          <button type="button" :class="['seg-btn', { active: selectedType === 'group' }]" @click="selectedType = 'group'">團課</button>
          <button type="button" :class="['seg-btn', { active: selectedType === 'private' }]" @click="selectedType = 'private'">私教課</button>
        </div>
      </div>
    </div>

    <!-- 預約表格 -->
    <div class="schedule-section">
      <div v-if="filteredReservations.length === 0" class="empty-state card">
        <h3>目前尚無預約紀錄</h3>
      </div>

      <div v-else class="table-container card" style="padding: 0; overflow-x: auto;">
        <table class="premium-table">
          <thead>
            <tr>
              <th style="width: 18%;">上課時間</th>
              <th style="width: 25%;">課程名稱</th>
              <th style="width: 10%;">上課教室</th>
              <th style="width: 12%;">指導教練</th>
              <th style="width: 10%;">狀態</th>
              <th style="width: 25%;">動作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="res in filteredReservations" :key="res.id" :class="{ 'row-expired': res.status === 'PENDING' && isReservationExpired(res) }">
              <td>
                <div style="font-weight: 700; color: var(--text-primary);">{{ res.reservationDate }}</div>
                <div style="font-size: 0.8rem; color: var(--text-muted);">{{ res.reservationTime }}</div>
              </td>
              <td>
                <div style="display: flex; align-items: center; justify-content: center; gap: 0.4rem;">
                  <CourseTypeBadge :course-type="isReservationPrivate(res) ? 'PRIVATE' : 'GROUP'" />
                  <span style="font-weight: 600;">{{ res.courseName || '一對一私人肌力課' }}</span>
                </div>
              </td>
              <td>{{ res.roomName || '現場安排' }}</td>
              <td>{{ res.trainerName }}</td>
              <td>
                <CourseTypeBadge
                  :status-name="res.status === 'PENDING' && isReservationExpired(res) ? '已過期' : res.statusName"
                  :status-type="res.status === 'PENDING' && isReservationExpired(res) ? 'neutral' : res.statusType"
                  style="margin: 0;"
                />
              </td>
              <td>
                <div style="display: inline-flex; gap: 0.4rem; align-items: center; justify-content: center; width: 100%;">
                  <button class="btn btn-ghost btn-sm" @click="showReservationDetail(res)">查看介紹</button>
                  
                  <!-- PENDING 狀態（且未過期）顯示簽到碼與取消按鈕 -->
                  <template v-if="res.status === 'PENDING' && !isReservationExpired(res)">
                    <div v-if="res.checkInKey" class="key-display-inline">
                      <span class="key-label-sm">簽到碼:</span>
                      <span class="key-code-sm">{{ res.checkInKey }}</span>
                    </div>
                    <button
                      v-else
                      class="btn btn-edit btn-sm"
                      @click="handleGenerateKey(res.id)"
                      :disabled="generatingKeyId === res.id"
                    >
                      產生簽到碼
                    </button>
                    <button class="btn btn-danger btn-sm" @click="openCancelModal(res)">取消</button>
                  </template>
                  
                  <!-- PENDING 狀態（且已過期）顯示無法取消 -->
                  <template v-else-if="res.status === 'PENDING' && isReservationExpired(res)">
                    <button class="btn btn-secondary btn-sm" disabled style="cursor: not-allowed; opacity: 0.6;">無法取消</button>
                  </template>
                  
                  <!-- 其他狀態顯示核銷/退費進度 -->
                  <template v-else-if="res.status === 'CANCEL_PENDING'">
                    <span style="font-size: 0.75rem; color: var(--text-muted);">預估退費: {{ (res.refundPercentage * 100).toFixed(0) }}%</span>
                  </template>
                  <template v-else-if="res.status === 'CANCELLED' && res.cancelApprover">
                    <span style="font-size: 0.75rem; color: var(--text-muted);">已退 {{ (res.refundPercentage * 100).toFixed(0) }}%</span>
                  </template>
                  <template v-else-if="res.status === 'COMPLETED' && res.checkInTime">
                    <span style="font-size: 0.75rem; color: var(--text-muted);">已核銷</span>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 取消預約 Modal -->
    <AppModal
      v-model:visible="showCancelModal"
      title="取消預約課程"
      size="sm"
      @close="closeCancelModal"
    >
      <div class="cancel-body">
        <div class="cancel-summary">
          <div class="summary-row"><span class="summary-label">課程</span><span>{{ cancelTarget?.courseName }}</span></div>
          <div class="summary-row"><span class="summary-label">時間</span><span>{{ cancelTarget?.reservationDate }} {{ cancelTarget?.reservationTime }}</span></div>
          <div class="summary-row"><span class="summary-label">教練</span><span>{{ cancelTarget?.trainerName }}</span></div>
          <div class="summary-row"><span class="summary-label">教室</span><span>{{ cancelTarget?.roomName }}</span></div>
        </div>

        <div class="cancel-alert" v-if="cancelHours !== null">
          <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-alert-triangle"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
          <div v-if="cancelHours >= 24">
            距離上課還有 <strong>{{ cancelHours }}</strong> 小時。
            <br>開課 24 小時前取消，將<strong>全額退回</strong>課程額度。
          </div>
          <div v-else>
            距離上課僅剩 <strong>{{ cancelHours }}</strong> 小時。
            <br>開課 24 小時內取消，將<strong>不予退費</strong>且扣除額度。
          </div>
        </div>

        <div class="form-group" style="margin-top: 1.2rem;">
          <label class="form-label required">請填寫取消原因</label>
          <textarea
            v-model="cancelReason"
            class="form-textarea"
            rows="3"
            placeholder="請輸入取消原因..."
          ></textarea>
        </div>
      </div>
      <template #footer>
        <div class="modal-footer-btns">
          <button class="btn btn-ghost" @click="closeCancelModal">再想想</button>
          <button
            class="btn btn-danger"
            :disabled="!cancelReason.trim() || submittingCancel"
            @click="handleCancelSubmit"
          >
            {{ submittingCancel ? '取消中...' : '確認取消預約' }}
          </button>
        </div>
      </template>
    </AppModal>

    <!-- 預約詳情 Modal -->
    <AppModal
      v-model:visible="showDetailModal"
      title="預約課程詳情"
      size="md"
    >
      <div v-if="activeDetailRes" class="res-detail-modal-body">
        <div class="detail-info-grid">
          <div class="detail-info-item">
            <span class="info-label">課程名稱</span>
            <span class="info-value" style="font-weight: 700;">{{ activeDetailRes.courseName }}</span>
          </div>
          <div class="detail-info-item" v-if="activeDetailRes.roomName">
            <span class="info-label">上課教室</span>
            <span class="info-value">{{ activeDetailRes.roomName }}</span>
          </div>
          <div class="detail-info-item">
            <span class="info-label">預約時間</span>
            <span class="info-value">{{ activeDetailRes.reservationDate }} {{ activeDetailRes.reservationTime }}</span>
          </div>
        </div>

        <!-- 教練介紹 -->
        <div class="detail-trainer-section" v-if="activeDetailRes.trainerName">
          <div class="detail-section-title">關於教練</div>
          <div class="detail-trainer-card">
            <img :src="activeDetailRes.trainerAvatarUrl || 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=200&auto=format&fit=crop&q=60'" class="detail-trainer-avatar" alt="教練頭像" />
            <div class="detail-trainer-details">
              <div class="detail-trainer-name">{{ activeDetailRes.trainerName }}</div>
              <div class="detail-trainer-exp" v-if="activeDetailRes.trainerExperience">資歷：{{ activeDetailRes.trainerExperience }}</div>
              <div class="detail-trainer-spec" v-if="activeDetailRes.trainerSpecialty">專長：{{ activeDetailRes.trainerSpecialty }}</div>
              <div v-if="activeDetailRes.trainerBio" class="detail-trainer-bio">{{ activeDetailRes.trainerBio }}</div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="btn btn-primary" @click="showDetailModal = false">關閉</button>
      </template>
    </AppModal>
  </div>
</template>

<script setup>
import { ref, computed, inject } from 'vue'
import { generateCheckInKey, requestCancel } from '@/api/member/booking.js'
import CourseTypeBadge from '@/components/CourseTypeBadge.vue'
import AppModal from '@/components/AppModal.vue'
import BaseSelect from '@/components/BaseSelect.vue'

const props = defineProps({
  reservations: {
    type: Array,
    required: true
  }
})

const emit = defineEmits(['refresh'])
const showToast = inject('showToast')

const searchQuery = ref('')
const selectedTrainerId = ref('')
const selectedType = ref('')
const startDate = ref('')
const endDate = ref('')
const subTab = ref('all') // 'all' | 'upcoming' | 'expired' | 'completed' | 'cancelled'

const generatingKeyId = ref(null)

const showCancelModal = ref(false)
const cancelTarget = ref(null)
const cancelReason = ref('')
const cancelHours = ref(null)
const submittingCancel = ref(false)

const activeDetailRes = ref(null)
const showDetailModal = ref(false)

function showReservationDetail(res) {
  activeDetailRes.value = res
  showDetailModal.value = true
}

function isReservationPrivate(r) {
  if (!r.courseName) return false
  const n = r.courseName
  return n.includes('一對一') || n.includes('私教') || n.includes('1對1') || n.includes('1 對 1')
}

function isReservationExpired(r) {
  if (!r.reservationDate || !r.reservationTime) return false
  try {
    const timeParts = r.reservationTime.split('-')
    const endTimeStr = timeParts[1] ? timeParts[1].trim() : '00:00'
    const endDateTime = new Date(`${r.reservationDate}T${endTimeStr}:00`)
    return endDateTime < new Date()
  } catch (e) {
    return false
  }
}

// Extract trainers from reservations
const trainers = computed(() => {
  const map = new Map()
  props.reservations.forEach(r => {
    if (r.trainerId && r.trainerName) {
      map.set(r.trainerId, {
        id: r.trainerId,
        name: r.trainerName
      })
    }
  })
  return Array.from(map.values())
})

// Apply keyword, trainer, type filters
const baseFiltered = computed(() => {
  return props.reservations.filter(r => {
    if (searchQuery.value) {
      const q = searchQuery.value.trim().toLowerCase()
      const courseNameMatch = r.courseName?.toLowerCase().includes(q)
      const trainerNameMatch = r.trainerName?.toLowerCase().includes(q)
      if (!courseNameMatch && !trainerNameMatch) return false
    }
    if (selectedTrainerId.value) {
      if (String(r.trainerId) !== String(selectedTrainerId.value)) return false
    }
    if (selectedType.value) {
      const isPrivate = isReservationPrivate(r)
      if (selectedType.value === 'private' && !isPrivate) return false
      if (selectedType.value === 'group' && isPrivate) return false
    }
    if (startDate.value && r.reservationDate) {
      if (r.reservationDate < startDate.value) return false
    }
    if (endDate.value && r.reservationDate) {
      if (r.reservationDate > endDate.value) return false
    }
    return true
  })
})

const upcomingCount = computed(() => baseFiltered.value.filter(r => r.status === 'PENDING' && !isReservationExpired(r)).length)
const expiredCount = computed(() => baseFiltered.value.filter(r => r.status === 'PENDING' && isReservationExpired(r)).length)
const completedCount = computed(() => baseFiltered.value.filter(r => r.status === 'COMPLETED').length)
const cancelledCount = computed(() => baseFiltered.value.filter(r => r.status === 'CANCELLED' || r.status === 'CANCEL_PENDING').length)

const filteredReservations = computed(() => {
  const sorted = baseFiltered.value.sort((a, b) => {
    const aT = a.reservationTime?.split('-')[0].trim() ?? '00:00'
    const bT = b.reservationTime?.split('-')[0].trim() ?? '00:00'
    return new Date(`${b.reservationDate}T${bT}`) - new Date(`${a.reservationDate}T${aT}`)
  })
  
  if (subTab.value === 'upcoming') {
    return sorted.filter(r => r.status === 'PENDING' && !isReservationExpired(r))
      .sort((a, b) => {
        const aT = a.reservationTime?.split('-')[0].trim() ?? '00:00'
        const bT = b.reservationTime?.split('-')[0].trim() ?? '00:00'
        return new Date(`${a.reservationDate}T${aT}`) - new Date(`${b.reservationDate}T${bT}`)
      })
  }
  if (subTab.value === 'expired') {
    return sorted.filter(r => r.status === 'PENDING' && isReservationExpired(r))
  }
  if (subTab.value === 'completed') {
    return sorted.filter(r => r.status === 'COMPLETED')
  }
  if (subTab.value === 'cancelled') {
    return sorted.filter(r => r.status === 'CANCELLED' || r.status === 'CANCEL_PENDING')
  }
  return sorted
})

async function handleGenerateKey(resId) {
  generatingKeyId.value = resId
  try {
    const res = await generateCheckInKey(resId)
    showToast('簽到碼產生成功：' + res.data, 'success')
    emit('refresh')
  } catch (error) {
    showToast(error.response?.data || '產生失敗', 'danger')
  } finally {
    generatingKeyId.value = null
  }
}

function openCancelModal(res) {
  cancelTarget.value = res
  cancelReason.value = ''
  
  // Calculate hours left
  try {
    const startTimeStr = res.reservationTime.split('-')[0].trim()
    const targetDateTime = new Date(`${res.reservationDate}T${startTimeStr}:00`)
    const diffMs = targetDateTime - new Date()
    const diffHours = diffMs / (1000 * 60 * 60)
    cancelHours.value = diffHours > 0 ? Math.ceil(diffHours) : 0
  } catch (e) {
    cancelHours.value = null
  }
  
  showCancelModal.value = true
}

function closeCancelModal() {
  showCancelModal.value = false
  cancelTarget.value = null
  cancelReason.value = ''
}

async function handleCancelSubmit() {
  if (!cancelReason.value.trim()) return
  submittingCancel.value = true
  try {
    await requestCancel(cancelTarget.value.id, cancelReason.value)
    showToast('取消預約成功', 'success')
    showCancelModal.value = false
    emit('refresh')
  } catch (error) {
    showToast(error.response?.data || '取消失敗', 'danger')
  } finally {
    submittingCancel.value = false
  }
}
</script>

<style scoped>
/* ── Filter Panel Styles ── */
.filter-panel {
  margin-bottom: 1.5rem;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 1.25rem;
  align-items: flex-end;
}
.filter-item {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  flex: 1;
  min-width: 180px;
}
.clear-btn {
  position: absolute;
  right: 0.6rem;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
}

/* ── Segmented Control ── */
.segmented-control {
  display: flex;
  background: var(--bg-hover, #f1f5f9);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-sm, 6px);
  padding: 0.2rem;
  width: 100%;
  height: 38px;
  align-items: center;
}
.seg-btn {
  flex: 1;
  background: none;
  border: none;
  padding: 0.35rem 0.75rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  border-radius: calc(var(--radius-sm, 6px) - 2px);
  cursor: pointer;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.seg-btn.active {
  background: var(--bg-section, #fff);
  color: var(--primary);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.05));
}

/* ── Premium Table Styles ── */
.schedule-section {
  min-height: 380px;
  display: flex;
  flex-direction: column;
}

.schedule-section > .empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
  background: var(--bg-section, #fff);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-lg, 12px);
  margin-top: 1rem;
}

.table-container {
  overflow: hidden;
  border-radius: var(--radius-lg, 12px);
  border: 1px solid var(--border-color, #e2e8f0);
  box-shadow: var(--shadow-sm);
  background: var(--bg-section, #fff);
  margin-top: 1rem;
  flex: 1;
}

.premium-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
  table-layout: fixed; /* 固定欄寬，防止內容字數不同導致欄位位移 */
}

.premium-table th {
  background: var(--bg-hover, #f8fafc);
  font-weight: 700;
  color: var(--text-secondary);
  padding: 0.75rem 0.85rem;
  border-bottom: 2px solid var(--border-color, #e2e8f0);
  text-align: center; /* 標題置中 */
}

.premium-table td {
  padding: 0.75rem 0.85rem;
  border-bottom: 1px solid var(--border-color, #e2e8f0);
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
  background-color: var(--bg-hover, #f8fafc);
}

/* ── Sub Tab Controls ── */
.sub-tab-control {
  display: flex;
  background: var(--bg-hover, #f1f5f9);
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
  background: var(--bg-hover, #f8fafc);
  border: 1px solid var(--border-color, #e2e8f0);
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
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

/* Modal styles custom styles */
.cancel-body {
  padding: 0.5rem 0;
}
.cancel-summary {
  background: var(--bg-hover, #f8fafc);
  padding: 0.75rem 1rem;
  border-radius: 8px;
  margin-bottom: 1rem;
}
.summary-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0.4rem;
  font-size: 0.9rem;
}
.summary-row:last-child {
  margin-bottom: 0;
}
.summary-label {
  color: var(--text-secondary);
}
.cancel-alert {
  display: flex;
  gap: 0.5rem;
  background: #fffbeb;
  border: 1px solid #fef3c7;
  color: #b45309;
  padding: 0.75rem 1rem;
  border-radius: 8px;
  font-size: 0.85rem;
  line-height: 1.4;
}

.res-detail-modal-body {
  padding: 0.5rem 0;
}
.detail-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  background: var(--bg-hover, #f8fafc);
  padding: 1rem;
  border-radius: 8px;
}
.detail-info-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.info-label {
  font-size: 0.78rem;
  color: var(--text-secondary);
}
.info-value {
  font-size: 0.95rem;
  color: var(--text-primary);
}
.detail-trainer-section {
  margin-top: 1.25rem;
}
.detail-section-title {
  font-size: 0.95rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
  color: var(--text-primary);
}
.detail-trainer-card {
  display: flex;
  gap: 1rem;
  background: var(--bg-hover, #f8fafc);
  padding: 1rem;
  border-radius: 8px;
  align-items: flex-start;
}
.detail-trainer-avatar {
  width: 60px;
  height: 60px;
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
  border-top: 1px solid var(--border-color, #e2e8f0);
  padding-top: 0.4rem;
}

@media (max-width: 768px) {
  .premium-table th, .premium-table td {
    padding: 0.6rem;
    font-size: 0.82rem;
  }
}

/* ── 篩選與排版 ── */
.filter-panel {
  background: var(--bg-section, #fff);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 1.25rem 1.75rem;
  margin-bottom: 2rem;
  box-shadow: var(--shadow-sm);
}
.filter-row { display: flex; flex-wrap: wrap; gap: 1rem; align-items: flex-end; }
.filter-item { display: flex; flex-direction: column; gap: 0.35rem; flex: 1; min-width: 140px; }
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
</style>
