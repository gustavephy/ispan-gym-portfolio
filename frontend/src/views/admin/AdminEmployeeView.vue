<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  getEmployees,
  getEmployeeById,
  updateEmployee,
  changeEmployeeRole,
  suspendEmployee,
  activateEmployee,
} from '@/api/adminEmployee.js'
import { getMyAccount } from '@/api/account.js'
import { useAuthStore } from '@/stores/auth.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import { useFeedback } from '@/composables/useFeedback.js'
import StaffManagementToolbar from '@/components/StaffManagementToolbar.vue'
import StaffStatusBadge from '@/components/StaffStatusBadge.vue'
import StaffChangeConfirmModal from '@/components/StaffChangeConfirmModal.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import Pagination from '@/components/Pagination.vue'
import AppModal from '@/components/AppModal.vue'
import EmptyState from '@/components/EmptyState.vue'
import OperationMessage from '@/components/OperationMessage.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'
import {
  TW_MOBILE_MAXLENGTH,
  TW_MOBILE_PLACEHOLDER,
  TW_MOBILE_ERROR,
  isValidTwMobile,
} from '@/utils/phone.js'

/**
 * ADMIN 員工管理頁，對應 AdminEmployeeController（/api/admin/employees）。
 * 列表／詳細／代改個資／角色調整（EMPLOYEE⇄ADMIN）／停用／恢復。
 *
 * 刻意不從 AdminMemberView 複製：員工有 PENDING_APPROVAL／REJECTED 兩個屬審核流程
 * 的狀態（本頁不處理，導去員工審核頁）、有「最後一位有效 ADMIN」與「具有效教練身分」
 * 兩道停用/降級限制、還有「ADMIN 對自己操作後要同步 auth store」的情形。
 */

const auth = useAuthStore()
const router = useRouter()
const { feedback, showFeedback } = useFeedback()

// 與後端 EmployeeStatus / EmployeeRole 對齊
const STATUS_OPTIONS = [
  { label: '全部狀態', value: '' },
  { label: '在職', value: 'ACTIVE' },
  { label: '待審核', value: 'PENDING_APPROVAL' },
  { label: '已拒絕', value: 'REJECTED' },
  { label: '已停用', value: 'INACTIVE' },
]
const ROLE_OPTIONS = [
  { label: '全部角色', value: '' },
  { label: '一般員工', value: 'EMPLOYEE' },
  { label: '管理員', value: 'ADMIN' },
]
// 與後端 AdminEmployeeService.ALLOWED_SORTS 一致
const SORT_OPTIONS = [
  { label: '員工編號', value: 'id' },
  { label: '姓名', value: 'name' },
  { label: '到職日', value: 'hireDate' },
  { label: '建立時間', value: 'createdAt' },
]
const DIRECTION_OPTIONS = [
  { label: '遞增', value: 'asc' },
  { label: '遞減', value: 'desc' },
]

const rows = ref([])
const loading = ref(true)
const listError = ref('')

const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

const keyword = ref('')
const statusFilter = ref('')
const roleFilter = ref('')
const sort = ref('id')
const direction = ref('asc')

let keywordTimer = null

const emptyMessage = computed(() =>
  keyword.value || statusFilter.value || roleFilter.value
    ? '查無符合條件的員工'
    : '目前沒有任何員工資料',
)

async function fetchList() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await getEmployees({
      keyword: keyword.value,
      status: statusFilter.value,
      role: roleFilter.value,
      page: page.value,
      size: size.value,
      sort: sort.value,
      direction: direction.value,
    })
    rows.value = data.content
    totalItems.value = data.totalElements
    totalPages.value = data.totalPages
  } catch (e) {
    listError.value = e.message
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    page.value = 0
    fetchList()
  }, 400)
})

watch([statusFilter, roleFilter, sort, direction], () => {
  page.value = 0
  fetchList()
})

function goPage(p) {
  page.value = p
  fetchList()
}

function changeSize(s) {
  size.value = s
  page.value = 0
  fetchList()
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  roleFilter.value = ''
  sort.value = 'id'
  direction.value = 'asc'
  page.value = 0
  fetchList()
}

function formatDate(value) {
  return value ? value.slice(0, 10).replace(/-/g, '/') : '—'
}

/** 操作對象是不是目前登入帳號——用來決定成功後要不要刷新 auth store 的 roles */
function isSelf(email) {
  return !!email && !!auth.email && email.toLowerCase() === auth.email.toLowerCase()
}

/**
 * 若操作對象是目前登入帳號，用 /api/account/me 的最新結果覆蓋 auth store 的 roles。
 * @returns {Promise<boolean>} true 代表仍具 ADMIN、可留在本頁；false 代表已失去 ADMIN
 *   權限並已導離（呼叫端不可再抓 ADMIN 列表，否則會拿到 403）。
 */
async function syncSelfAndKeepAccess(email) {
  if (!isSelf(email)) return true
  try {
    const { data } = await getMyAccount()
    auth.syncAccount(data)
  } catch (e) {
    // 同步失敗（含帳號剛被自己停用而 401 被攔截器登出）：不再往下抓 ADMIN 列表，
    // 交由攔截器／路由守衛把使用者帶去正確的地方
    console.error('同步目前帳號角色失敗', e)
    return false
  }
  if (!auth.isAdmin) {
    // 剛把自己降級或停用：已無權停留在 /admin/*，導回首頁，不再重抓列表
    router.push('/')
    return false
  }
  return true
}

// ---- 編輯 ----
const editModal = ref(false)
const editLoading = ref(false)
const saving = ref(false)
const editError = ref('')
const editForm = ref(null) // { employeeId, email, role, status, reviewedBy, reviewNote, firstName, lastName, phone, hireDate }
const editOriginal = ref(null) // 載入當下的快照，用來算實際變更欄位
const phoneMaxlength = TW_MOBILE_MAXLENGTH
const phonePlaceholder = TW_MOBILE_PLACEHOLDER

// 二次確認彈窗
const confirmModal = ref(false)
const confirmError = ref('')

const EDIT_FIELD_LABELS = {
  lastName: '姓氏',
  firstName: '名字',
  phone: '電話',
  hireDate: '到職日',
}

async function openEdit(employeeId) {
  editModal.value = true
  editLoading.value = true
  editError.value = ''
  editForm.value = null
  editOriginal.value = null
  try {
    const { data } = await getEmployeeById(employeeId)
    editForm.value = {
      employeeId: data.employeeId,
      email: data.email,
      role: data.role,
      status: data.status,
      isTrainer: data.isTrainer,
      reviewedBy: data.reviewedBy,
      reviewNote: data.reviewNote,
      firstName: data.firstName ?? '',
      lastName: data.lastName ?? '',
      phone: data.phone ?? '',
      hireDate: data.hireDate ?? '',
    }
    editOriginal.value = {
      firstName: editForm.value.firstName,
      lastName: editForm.value.lastName,
      phone: editForm.value.phone,
      hireDate: editForm.value.hireDate,
    }
  } catch (e) {
    editModal.value = false
    notifyApiError(e)
  } finally {
    editLoading.value = false
  }
}

const editValid = computed(() => {
  const f = editForm.value
  if (!f) return false
  return (
    f.firstName.trim() !== '' &&
    f.lastName.trim() !== '' &&
    isValidTwMobile(f.phone) &&
    f.hireDate !== ''
  )
})

/** 送出用 payload（四欄皆必填，PUT 為完整覆蓋語意） */
function editPayload() {
  const f = editForm.value
  return {
    firstName: f.firstName.trim(),
    lastName: f.lastName.trim(),
    phone: f.phone.trim(),
    hireDate: f.hireDate,
  }
}

const editChanges = computed(() => {
  if (!editForm.value || !editOriginal.value) return []
  const payload = editPayload()
  return Object.keys(EDIT_FIELD_LABELS)
    .filter((key) => (editOriginal.value[key] || '') !== (payload[key] || ''))
    .map((key) => ({
      key,
      label: EDIT_FIELD_LABELS[key],
      before: editOriginal.value[key],
      after: payload[key],
    }))
})

function onSaveEdit() {
  editError.value = ''
  if (!editForm.value || !editValid.value) {
    editError.value = `請確認姓名、電話（${TW_MOBILE_ERROR}）與到職日皆已正確填寫`
    return
  }
  if (editChanges.value.length === 0) {
    editError.value = '目前沒有需要儲存的變更'
    return
  }
  confirmError.value = ''
  confirmModal.value = true
}

async function confirmEditSave() {
  if (!editForm.value || editChanges.value.length === 0) return
  saving.value = true
  confirmError.value = ''
  try {
    await updateEmployee(editForm.value.employeeId, editPayload())
    showFeedback('員工資料已更新')
    confirmModal.value = false
    editModal.value = false
    fetchList()
  } catch (e) {
    if (isInlineError(e)) confirmError.value = e.message
    else {
      confirmModal.value = false
      notifyApiError(e)
    }
  } finally {
    saving.value = false
  }
}

// ---- 角色調整 ----
const roleModal = ref(false)
const roleTarget = ref(null) // 列資料
const roleBusy = ref(false)
const roleError = ref('')

const nextRole = computed(() => (roleTarget.value?.role === 'ADMIN' ? 'EMPLOYEE' : 'ADMIN'))
const nextRoleLabel = computed(() => (nextRole.value === 'ADMIN' ? '管理員' : '一般員工'))

function openRole(row) {
  roleTarget.value = row
  roleError.value = ''
  roleModal.value = true
}

async function confirmRole() {
  roleBusy.value = true
  roleError.value = ''
  try {
    await changeEmployeeRole(roleTarget.value.employeeId, nextRole.value)
    const email = roleTarget.value.email
    showFeedback(`已將 ${roleTarget.value.displayName} 調整為${nextRoleLabel.value}`)
    roleModal.value = false
    if (await syncSelfAndKeepAccess(email)) fetchList()
  } catch (e) {
    if (isInlineError(e)) roleError.value = e.message
    else notifyApiError(e)
  } finally {
    roleBusy.value = false
  }
}

// ---- 停用 / 恢復 ----
const statusModal = ref(false)
const statusMode = ref('suspend') // 'suspend' | 'activate'
const statusTarget = ref(null)
const statusBusy = ref(false)
const statusError = ref('')

function openStatus(row, mode) {
  statusTarget.value = row
  statusMode.value = mode
  statusError.value = ''
  statusModal.value = true
}

async function confirmStatus() {
  statusBusy.value = true
  statusError.value = ''
  const { employeeId, email, displayName } = statusTarget.value
  try {
    if (statusMode.value === 'suspend') {
      await suspendEmployee(employeeId)
      showFeedback(`已停用 ${displayName}`)
    } else {
      await activateEmployee(employeeId)
      showFeedback(`已恢復 ${displayName}`)
    }
    statusModal.value = false
    if (await syncSelfAndKeepAccess(email)) fetchList()
  } catch (e) {
    if (isInlineError(e)) statusError.value = e.message
    else notifyApiError(e)
  } finally {
    statusBusy.value = false
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container staff-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">員工管理</h1>
    <p class="section-desc">
      查詢與代改員工資料、調整角色（一般員工⇄管理員）、停用或恢復員工。待審核與已拒絕的申請請至
      <RouterLink to="/admin/employee-reviews">員工審核</RouterLink> 處理。
    </p>

    <StaffManagementToolbar
      v-model:keyword="keyword"
      keyword-placeholder="姓名／電話／Email"
      :total="totalItems"
      total-unit="位員工"
      @reset="resetFilters"
    >
      <template #filters>
        <label class="toolbar__field">
          <span class="toolbar__label">狀態</span>
          <BaseSelect v-model="statusFilter" :options="STATUS_OPTIONS" />
        </label>
        <label class="toolbar__field">
          <span class="toolbar__label">角色</span>
          <BaseSelect v-model="roleFilter" :options="ROLE_OPTIONS" />
        </label>
        <label class="toolbar__field">
          <span class="toolbar__label">排序</span>
          <BaseSelect v-model="sort" :options="SORT_OPTIONS" />
        </label>
        <label class="toolbar__field">
          <span class="toolbar__label">順序</span>
          <BaseSelect v-model="direction" :options="DIRECTION_OPTIONS" />
        </label>
      </template>
    </StaffManagementToolbar>

    <OperationMessage :message="listError" />

    <div class="card table-card">
      <div v-if="loading" class="spinner"></div>

      <EmptyState v-else-if="rows.length === 0" icon="search" :title="emptyMessage" />

      <table v-else class="data-table">
        <thead>
          <tr>
            <th>編號</th>
            <th>姓名</th>
            <th>Email</th>
            <th>電話</th>
            <th>角色</th>
            <th>狀態</th>
            <th>到職日</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.employeeId">
            <td>{{ r.employeeId }}</td>
            <td>
              {{ r.displayName }}
              <span v-if="isSelf(r.email)" class="self-tag">你</span>
            </td>
            <td class="col-email">{{ r.email }}</td>
            <td>{{ r.phone || '—' }}</td>
            <td><StaffStatusBadge kind="employeeRole" :value="r.role" /></td>
            <td><StaffStatusBadge kind="employeeStatus" :value="r.status" /></td>
            <td>{{ formatDate(r.hireDate) }}</td>
            <td class="col-actions">
              <button type="button" class="btn btn-sm" @click="openEdit(r.employeeId)">詳細／編輯</button>
              <button
                v-if="r.status === 'ACTIVE'"
                type="button"
                class="btn btn-sm"
                @click="openRole(r)"
              >
                {{ r.role === 'ADMIN' ? '設為一般員工' : '設為管理員' }}
              </button>
              <button
                v-if="r.status === 'ACTIVE'"
                type="button"
                class="btn btn-sm btn-danger"
                @click="openStatus(r, 'suspend')"
              >
                停用
              </button>
              <button
                v-else-if="r.status === 'INACTIVE'"
                type="button"
                class="btn btn-sm btn-accent"
                @click="openStatus(r, 'activate')"
              >
                恢復
              </button>
              <span
                v-else
                class="row-hint"
                title="待審核／已拒絕的申請請至員工審核頁處理"
              >審核流程</span>
            </td>
          </tr>
        </tbody>
      </table>

      <Pagination
        v-if="totalPages > 0 && !loading"
        :page="page"
        :size="size"
        :total-pages="totalPages"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </div>

    <!-- 編輯 -->
    <AppModal v-model:visible="editModal" title="員工詳細／編輯" icon="" size="md">
      <div v-if="editLoading" class="spinner"></div>
      <form v-else-if="editForm" id="employeeEditForm" class="modal-form" @submit.prevent="onSaveEdit">
        <dl class="modal-readonly">
          <div><dt>員工編號</dt><dd>#{{ editForm.employeeId }}</dd></div>
          <div><dt>Email</dt><dd>{{ editForm.email }}</dd></div>
          <div>
            <dt>角色</dt>
            <dd><StaffStatusBadge kind="employeeRole" :value="editForm.role" /></dd>
          </div>
          <div>
            <dt>狀態</dt>
            <dd><StaffStatusBadge kind="employeeStatus" :value="editForm.status" /></dd>
          </div>
          <div><dt>教練身分</dt><dd>{{ editForm.isTrainer ? '是' : '否' }}</dd></div>
          <div v-if="editForm.reviewedBy">
            <dt>審核者</dt><dd>{{ editForm.reviewedBy.displayName }}</dd>
          </div>
          <div v-if="editForm.reviewNote" class="modal-readonly__wide">
            <dt>審核備註</dt><dd>{{ editForm.reviewNote }}</dd>
          </div>
        </dl>

        <div class="modal-form__row">
          <div class="form-group">
            <label class="form-label" for="ee-lastName">姓氏</label>
            <input id="ee-lastName" v-model="editForm.lastName" class="form-input" maxlength="50" required />
          </div>
          <div class="form-group">
            <label class="form-label" for="ee-firstName">名字</label>
            <input id="ee-firstName" v-model="editForm.firstName" class="form-input" maxlength="50" required />
          </div>
        </div>

        <div class="modal-form__row">
          <div class="form-group">
            <label class="form-label" for="ee-phone">電話</label>
            <input
              id="ee-phone"
              v-model="editForm.phone"
              class="form-input"
              type="tel"
              :maxlength="phoneMaxlength"
              :placeholder="phonePlaceholder"
              required
            />
            <p v-if="editForm.phone && !isValidTwMobile(editForm.phone)" class="field-error">
              {{ TW_MOBILE_ERROR }}
            </p>
          </div>
          <div class="form-group">
            <label class="form-label" for="ee-hireDate">到職日</label>
            <input id="ee-hireDate" v-model="editForm.hireDate" class="form-input" type="date" required />
          </div>
        </div>

        <p class="modal-form__note">角色與狀態請用列表上的專用按鈕調整，不在此表單。</p>
        <OperationMessage :message="editError" tone="error" />
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="editModal = false">取消</button>
        <button
          type="submit"
          form="employeeEditForm"
          class="btn btn-primary"
          :disabled="saving || editLoading || !editValid"
        >
          儲存
        </button>
      </template>
    </AppModal>

    <!-- 儲存前二次確認：只列實際變更欄位 -->
    <StaffChangeConfirmModal
      v-model:visible="confirmModal"
      title="確認修改員工資料"
      intro="請確認以下變更，送出後將更新此員工的個人資料。"
      :changes="editChanges"
      :saving="saving"
      :error-message="confirmError"
      @confirm="confirmEditSave"
    />

    <!-- 角色調整 -->
    <AppModal v-model:visible="roleModal" title="調整員工角色" icon="" size="sm">
      <div v-if="roleTarget">
        <p class="confirm-text">
          將
          <strong>{{ roleTarget.displayName }}</strong>
          （{{ roleTarget.email }}）
          由
          <StaffStatusBadge kind="employeeRole" :value="roleTarget.role" />
          調整為
          <StaffStatusBadge kind="employeeRole" :value="nextRole" />
          ？
        </p>
        <p v-if="nextRole === 'ADMIN'" class="confirm-note">
          管理員可使用所有後台管理功能，包含員工與教練管理。
        </p>
        <p v-else class="confirm-note">
          降為一般員工後將失去後台管理權限（仍可使用一般員工與同儕審核功能）。
        </p>
        <p v-if="isSelf(roleTarget.email)" class="confirm-note confirm-note--warn">
          這是你目前登入的帳號，調整後你的選單與可用頁面會立即變動。
        </p>
        <OperationMessage :message="roleError" tone="error" />
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="roleModal = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="roleBusy" @click="confirmRole">
          {{ roleBusy ? '處理中…' : `確定調整為${nextRoleLabel}` }}
        </button>
      </template>
    </AppModal>

    <!-- 停用 / 恢復 -->
    <AppModal
      v-model:visible="statusModal"
      :title="statusMode === 'suspend' ? '停用員工' : '恢復員工'"
      icon=""
      size="sm"
    >
      <div v-if="statusTarget">
        <p class="confirm-text">
          {{ statusMode === 'suspend' ? '停用' : '恢復' }}
          <strong>{{ statusTarget.displayName }}</strong>
          （{{ statusTarget.email }}）？
        </p>
        <p v-if="statusMode === 'suspend'" class="confirm-note">
          停用後該員工將無法登入使用任何員工功能；歷史資料不會刪除，之後可再恢復。
          若該員工具有效教練身分，需先於教練管理釋出教練身分才能停用。
        </p>
        <p v-else class="confirm-note">恢復後該員工可再次登入使用員工功能。</p>
        <p v-if="isSelf(statusTarget.email)" class="confirm-note confirm-note--warn">
          這是你目前登入的帳號。
        </p>
        <OperationMessage :message="statusError" tone="error" />
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="statusModal = false">取消</button>
        <button
          type="button"
          class="btn"
          :class="statusMode === 'suspend' ? 'btn-danger' : 'btn-primary'"
          :disabled="statusBusy"
          @click="confirmStatus"
        >
          {{ statusBusy ? '處理中…' : statusMode === 'suspend' ? '確定停用' : '確定恢復' }}
        </button>
      </template>
    </AppModal>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.staff-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.table-card {
  padding: 1.5rem 1.75rem;
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 0.95rem;
}

.data-table th,
.data-table td {
  padding: 0.9rem 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.data-table th {
  font-size: 0.85rem;
  color: var(--text-secondary);
  font-weight: 700;
  background: var(--bg-soft);
}

.data-table tbody tr:last-child td {
  border-bottom: none;
}

.col-email {
  max-width: 16rem;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.self-tag {
  display: inline-block;
  margin-left: 0.35rem;
  padding: 0.05rem 0.45rem;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 0.7rem;
  font-weight: 700;
}

.row-hint {
  font-size: 0.82rem;
  color: var(--text-muted);
}

.btn-sm {
  padding: 0.4rem 0.85rem;
  font-size: 0.85rem;
  white-space: nowrap;
}

.btn-danger {
  background: #cf3b2c;
  color: #fff;
  border-color: #cf3b2c;
}

/* ---- Modal 內容 ---- */
.modal-form {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
}

.modal-readonly {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 0.75rem 1.25rem;
  margin: 0 0 0.5rem;
  padding: 0.9rem 1rem;
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
}

.modal-readonly div {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.modal-readonly__wide {
  grid-column: 1 / -1;
}

.modal-readonly dt {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--text-muted);
}

.modal-readonly dd {
  margin: 0;
  font-size: 0.88rem;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}

.modal-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.9rem;
}

.modal-form__row .form-group {
  flex: 1 1 12rem;
  margin-bottom: 0;
}

.modal-form__note {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.field-error {
  margin: 0.35rem 0 0;
  font-size: 0.78rem;
  font-weight: 600;
  color: #9b2c1e;
}

.confirm-text {
  margin: 0 0 0.75rem;
  font-size: 0.95rem;
  line-height: 1.7;
}

.confirm-note {
  margin: 0 0 0.5rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
  line-height: 1.6;
}

.confirm-note--warn {
  color: #9b2c1e;
  font-weight: 600;
}
</style>
