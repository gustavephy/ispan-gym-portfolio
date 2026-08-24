<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  getPendingEmployees,
  approveEmployee,
  rejectEmployee,
} from '@/api/employeeReview.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import { useFeedback } from '@/composables/useFeedback.js'
import OperationMessage from '@/components/OperationMessage.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'

// 後端 EmployeeRole：TRAINER 已併入 EMPLOYEE，是否為教練改由 trainer 表判斷
const ROLE_LABELS = { EMPLOYEE: '一般員工', ADMIN: '管理員' }

const pending = ref([])
const loading = ref(true)
const listError = ref(null)

// 開啟中的審核對話框：null 代表關閉
// { employee, mode: 'approve' | 'reject' }
const dialog = ref(null)
const noteInput = ref('')
const submitting = ref(false)
const dialogError = ref(null)

const { feedback, showFeedback } = useFeedback()

const isReject = computed(() => dialog.value?.mode === 'reject')

function displayName(e) {
  return `${e.lastName ?? ''}${e.firstName ?? ''}` || '（未填姓名）'
}

function formatDateTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(
    d.getDate(),
  ).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatDate(value) {
  return value ? value.slice(0, 10).replace(/-/g, '/') : '—'
}

async function fetchPending() {
  loading.value = true
  listError.value = null
  try {
    const { data } = await getPendingEmployees()
    pending.value = data
  } catch (e) {
    listError.value = e.message
  } finally {
    loading.value = false
  }
}

function openDialog(employee, mode) {
  dialog.value = { employee, mode }
  noteInput.value = ''
  dialogError.value = null
}

function closeDialog() {
  if (submitting.value) return
  dialog.value = null
  dialogError.value = null
}

async function onSubmit() {
  const { employee, mode } = dialog.value
  // 拒絕原因是後端 @NotBlank，前端先擋一次，不必等一趟往返才看到錯誤
  if (mode === 'reject' && !noteInput.value.trim()) {
    dialogError.value = '請填寫拒絕原因'
    return
  }
  submitting.value = true
  dialogError.value = null
  try {
    if (mode === 'reject') {
      await rejectEmployee(employee.employeeId, noteInput.value.trim())
      showFeedback(`已拒絕 ${displayName(employee)} 的申請`)
    } else {
      await approveEmployee(employee.employeeId, noteInput.value.trim())
      showFeedback(`已核准 ${displayName(employee)}`)
    }
    dialog.value = null
    fetchPending()
  } catch (e) {
    // 驗證與業務錯誤留在對話框內顯示，其餘交給全域提示
    if (isInlineError(e)) {
      dialogError.value = e.message
    } else {
      notifyApiError(e)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(fetchPending)
</script>

<template>
  <div class="page-container employee-review-page">
    <span class="section-tag">EMPLOYEE</span>
    <h1 class="section-title">員工申請審核</h1>
    <p class="section-desc">
      審核新註冊的員工帳號。核准後該帳號才能登入使用員工功能；拒絕後申請人可重新提出申請。
    </p>

    <OperationMessage :message="listError" />

    <div v-if="loading" class="spinner"></div>

    <p v-else-if="pending.length === 0" class="empty-hint card">
      目前沒有待審核的員工申請。
    </p>

    <!-- 待審核通常只有幾筆，且審核前要看完整申請資料，用卡片比表格好讀 -->
    <ul v-else class="review-list">
      <li v-for="e in pending" :key="e.employeeId" class="review-card card">
        <div class="review-card__head">
          <h2 class="review-card__name">{{ displayName(e) }}</h2>
          <span class="role-badge">{{ ROLE_LABELS[e.role] ?? e.role }}</span>
        </div>

        <dl class="review-card__info">
          <div><dt>Email</dt><dd>{{ e.email }}</dd></div>
          <div><dt>電話</dt><dd>{{ e.phone || '—' }}</dd></div>
          <div><dt>到職日</dt><dd>{{ formatDate(e.hireDate) }}</dd></div>
          <div><dt>申請時間</dt><dd>{{ formatDateTime(e.createdAt) }}</dd></div>
        </dl>

        <div class="review-card__actions">
          <button type="button" class="btn" @click="openDialog(e, 'reject')">拒絕</button>
          <button type="button" class="btn btn-accent" @click="openDialog(e, 'approve')">
            核准
          </button>
        </div>
      </li>
    </ul>

    <!-- 審核對話框：核准與拒絕共用，差別在標題、必填與否 -->
    <div v-if="dialog" class="dialog-overlay" @click.self="closeDialog">
      <section class="dialog card">
        <header class="dialog__header">
          <h2>{{ isReject ? '拒絕申請' : '核准申請' }}</h2>
          <button type="button" class="dialog__close" @click="closeDialog">×</button>
        </header>

        <p class="dialog__target">
          申請人：<strong>{{ displayName(dialog.employee) }}</strong>（{{ dialog.employee.email }}）
        </p>

        <form @submit.prevent="onSubmit">
          <label class="dialog__field">
            <span>{{ isReject ? '拒絕原因 *' : '審核備註（選填）' }}</span>
            <textarea
              v-model="noteInput"
              rows="3"
              maxlength="500"
              :placeholder="isReject ? '請說明拒絕的原因，申請人會看到這則訊息' : ''"
            ></textarea>
          </label>

          <OperationMessage :message="dialogError" />

          <div class="dialog__actions">
            <button type="button" class="btn" :disabled="submitting" @click="closeDialog">
              取消
            </button>
            <button
              type="submit"
              class="btn"
              :class="isReject ? 'btn-danger' : 'btn-accent'"
              :disabled="submitting"
            >
              {{ submitting ? '處理中…' : isReject ? '確定拒絕' : '確定核准' }}
            </button>
          </div>
        </form>
      </section>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.employee-review-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.empty-hint {
  padding: 3rem 1.75rem;
  text-align: center;
  color: var(--text-secondary);
}

/* ---- 卡片列表 ---- */
.review-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1rem;
}

.review-card {
  padding: 1.5rem 1.75rem;
}

.review-card__head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.review-card__name {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 800;
}

.role-badge {
  padding: 0.2rem 0.7rem;
  border-radius: 999px;
  background: var(--bg-soft);
  color: var(--text-secondary);
  font-size: 0.78rem;
  font-weight: 700;
}

.review-card__info {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: 0.75rem 1.5rem;
  margin: 0 0 1.25rem;
}

.review-card__info div {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.review-card__info dt {
  font-size: 0.78rem;
  color: var(--text-secondary);
}

.review-card__info dd {
  margin: 0;
  font-size: 0.92rem;
  /* Email 可能很長，窄螢幕時讓它斷行而不是撐破卡片 */
  overflow-wrap: anywhere;
}

.review-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
}

.btn-danger {
  background: #cf3b2c;
  color: #fff;
  border-color: #cf3b2c;
}

/* ---- 對話框 ---- */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(30, 24, 20, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 200;
}

.dialog {
  width: min(32rem, 100%);
  padding: 1.25rem 1.5rem 1.5rem;
}

.dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.75rem;
}

.dialog__header h2 {
  margin: 0;
  font-size: 1.1rem;
}

.dialog__close {
  background: none;
  border: none;
  font-size: 1.6rem;
  line-height: 1;
  cursor: pointer;
  color: var(--text-secondary);
}

.dialog__target {
  margin: 0 0 1rem;
  padding: 0.6rem 0.8rem;
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
}

.dialog__field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  margin-bottom: 0.9rem;
}

.dialog__field span {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.dialog__field textarea {
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 0.95rem;
  resize: vertical;
}

.dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
}
</style>
