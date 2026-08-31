<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  getTrainers,
  getTrainerById,
  updateTrainer,
  changeTrainerStatus,
  checkTrainerSuspension,
  suspendTrainer,
  restoreTrainer,
} from '@/api/adminTrainer.js'
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

/**
 * ADMIN 教練管理頁，對應 AdminTrainerController（/api/admin/trainers）。
 * 列表／詳細／代改專業資料／可服務狀態切換／停用前跨模組阻擋預檢／停用（軟刪除）／恢復。
 *
 * 刻意不從 AdminMemberView 複製：教練的「停用」是軟刪除且停用前要跨四個模組
 * （排班／私教預約／私教課／團課場次）做阻擋預檢並逐項呈現；「恢復」固定回到
 * UNAVAILABLE 而非原狀態；狀態機是 ACTIVE / UNAVAILABLE + 軟刪除三態，不是單純 enum。
 */

const auth = useAuthStore()
const router = useRouter()
const { feedback, showFeedback } = useFeedback()

const STATUS_OPTIONS = [
  { label: '全部狀態', value: '' },
  { label: '可服務', value: 'ACTIVE' },
  { label: '暫停服務', value: 'UNAVAILABLE' },
]
const SCOPE_OPTIONS = [
  { label: '在職教練', value: 'false' },
  { label: '已停用教練', value: 'true' },
]
// 與後端 AdminTrainerService.ALLOWED_SORTS 一致
const SORT_OPTIONS = [
  { label: '教練編號', value: 'trainerId' },
  { label: '姓名', value: 'name' },
  { label: '狀態', value: 'status' },
  { label: '建立時間', value: 'createdAt' },
  { label: '更新時間', value: 'updatedAt' },
]
const DIRECTION_OPTIONS = [
  { label: '遞增', value: 'asc' },
  { label: '遞減', value: 'desc' },
]

const BLOCKER_LABELS = {
  openSlots: '未完成的排班時段',
  activeBookings: '進行中的私教預約',
  activePrivateCourses: '上架中的私教課方案',
  upcomingGroupClassSessions: '即將到來的團課場次',
}
const BLOCKER_HINTS = {
  openSlots: '請至該教練的排課查詢確認並請教練自行取消未使用的時段。',
  activeBookings: '請等待預約完成或協助會員取消。',
  activePrivateCourses: '請至課程方案管理將對應私教課下架。',
  upcomingGroupClassSessions: '請至團課場次管理改期或取消。',
}

const rows = ref([])
const loading = ref(true)
const listError = ref('')

const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

const keyword = ref('')
const statusFilter = ref('')
const scope = ref('false') // 'false' = 在職, 'true' = 已停用
const sort = ref('trainerId')
const direction = ref('asc')

let keywordTimer = null

const showingDeleted = computed(() => scope.value === 'true')
const emptyMessage = computed(() =>
  keyword.value || statusFilter.value
    ? '查無符合條件的教練'
    : showingDeleted.value
      ? '目前沒有已停用的教練'
      : '目前沒有在職教練',
)

async function fetchList() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await getTrainers({
      keyword: keyword.value,
      status: statusFilter.value,
      deleted: scope.value === 'true',
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

watch([statusFilter, scope, sort, direction], () => {
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
  scope.value = 'false'
  sort.value = 'trainerId'
  direction.value = 'asc'
  page.value = 0
  fetchList()
}

function isSelf(email) {
  return !!email && !!auth.email && email.toLowerCase() === auth.email.toLowerCase()
}

/**
 * 若操作對象是目前登入帳號，用 /api/account/me 覆蓋 auth store 的 roles。
 * 停用自己的教練身分只會失去 ROLE_TRAINER、不影響 ADMIN，但仍統一走這道檢查：
 * @returns {Promise<boolean>} true 代表仍具 ADMIN、可留在本頁；false 代表已導離。
 */
async function syncSelfAndKeepAccess(email) {
  if (!isSelf(email)) return true
  try {
    const { data } = await getMyAccount()
    auth.syncAccount(data)
  } catch (e) {
    console.error('同步目前帳號角色失敗', e)
    return false
  }
  if (!auth.isAdmin) {
    router.push('/')
    return false
  }
  return true
}

// ---- 詳細 / 編輯 ----
const editModal = ref(false)
const editLoading = ref(false)
const saving = ref(false)
const editError = ref('')
const detail = ref(null)
const editForm = ref({ specialty: '', experience: '', bio: '', profileImageUrl: '' })
const editOriginal = ref(null)

// 二次確認彈窗
const confirmModal = ref(false)
const confirmError = ref('')

const EDIT_FIELD_LABELS = {
  specialty: '專長',
  experience: '經歷',
  bio: '自我介紹',
  profileImageUrl: '個人圖片網址',
}

const isDeletedDetail = computed(() => !!detail.value?.deletedAt)

async function openDetail(trainerId) {
  editModal.value = true
  editLoading.value = true
  editError.value = ''
  detail.value = null
  editOriginal.value = null
  try {
    const { data } = await getTrainerById(trainerId)
    detail.value = data
    editForm.value = {
      specialty: data.specialty || '',
      experience: data.experience || '',
      bio: data.bio || '',
      profileImageUrl: data.profileImageUrl || '',
    }
    editOriginal.value = { ...editForm.value }
  } catch (e) {
    editModal.value = false
    notifyApiError(e)
  } finally {
    editLoading.value = false
  }
}

const editValid = computed(
  () =>
    editForm.value.specialty.trim() !== '' &&
    editForm.value.experience.trim() !== '' &&
    editForm.value.bio.trim() !== '' &&
    editForm.value.specialty.length <= 255 &&
    editForm.value.experience.length <= 1000 &&
    editForm.value.bio.length <= 5000 &&
    editForm.value.profileImageUrl.length <= 255,
)

/** 送出用 payload（profileImageUrl 空白代表清除，送 null） */
function editPayload() {
  return {
    specialty: editForm.value.specialty.trim(),
    experience: editForm.value.experience.trim(),
    bio: editForm.value.bio.trim(),
    profileImageUrl: editForm.value.profileImageUrl.trim() || null,
  }
}

const editChanges = computed(() => {
  if (!editOriginal.value) return []
  const payload = editPayload()
  return Object.keys(EDIT_FIELD_LABELS)
    .filter((key) => (editOriginal.value[key]?.trim() || null) !== payload[key])
    .map((key) => ({
      key,
      label: EDIT_FIELD_LABELS[key],
      before: editOriginal.value[key],
      after: payload[key],
    }))
})

function onSaveEdit() {
  editError.value = ''
  if (!editValid.value) {
    editError.value = '請確認專長、經歷、自我介紹皆已填寫且長度符合限制'
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
  if (editChanges.value.length === 0) return
  saving.value = true
  confirmError.value = ''
  try {
    const { data } = await updateTrainer(detail.value.trainerId, editPayload())
    detail.value = data
    editForm.value = {
      specialty: data.specialty || '',
      experience: data.experience || '',
      bio: data.bio || '',
      profileImageUrl: data.profileImageUrl || '',
    }
    editOriginal.value = { ...editForm.value }
    showFeedback('教練資料已更新')
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

// ---- 可服務狀態切換 ----
const statusModal = ref(false)
const statusTarget = ref(null)
const statusBusy = ref(false)
const statusError = ref('')
const nextStatus = computed(() =>
  statusTarget.value?.status === 'ACTIVE' ? 'UNAVAILABLE' : 'ACTIVE',
)
const nextStatusLabel = computed(() => (nextStatus.value === 'ACTIVE' ? '可服務' : '暫停服務'))

function openStatusToggle(row) {
  statusTarget.value = row
  statusError.value = ''
  statusModal.value = true
}

async function confirmStatusToggle() {
  statusBusy.value = true
  statusError.value = ''
  try {
    await changeTrainerStatus(statusTarget.value.trainerId, nextStatus.value)
    showFeedback(`已將 ${statusTarget.value.displayName} 設為${nextStatusLabel.value}`)
    statusModal.value = false
    fetchList()
  } catch (e) {
    if (isInlineError(e)) statusError.value = e.message
    else notifyApiError(e)
  } finally {
    statusBusy.value = false
  }
}

// ---- 停用（含跨模組阻擋預檢） ----
const suspendModal = ref(false)
const suspendTarget = ref(null)
const checkLoading = ref(false)
const checkResult = ref(null) // TrainerSuspensionCheckResponse
const suspendBusy = ref(false)
const suspendError = ref('')

const blockerList = computed(() => {
  const b = checkResult.value?.blockers
  if (!b) return []
  return Object.keys(BLOCKER_LABELS).map((key) => ({
    key,
    label: BLOCKER_LABELS[key],
    hint: BLOCKER_HINTS[key],
    count: b[key] ?? 0,
  }))
})
const hasBlocker = computed(() => blockerList.value.some((x) => x.count > 0))
const canSuspend = computed(() => checkResult.value?.canSuspend === true && !hasBlocker.value)

async function openSuspend(row) {
  suspendTarget.value = row
  suspendError.value = ''
  checkResult.value = null
  suspendModal.value = true
  await runCheck()
}

async function runCheck() {
  checkLoading.value = true
  try {
    const { data } = await checkTrainerSuspension(suspendTarget.value.trainerId)
    checkResult.value = data
  } catch (e) {
    // 預檢本身失敗（例如教練已被別的管理員停用）：顯示錯誤、禁止繼續
    suspendError.value = e.message
    checkResult.value = { canSuspend: false, blockers: null }
  } finally {
    checkLoading.value = false
  }
}

async function confirmSuspend() {
  suspendBusy.value = true
  suspendError.value = ''
  const { trainerId, email, displayName } = suspendTarget.value
  try {
    await suspendTrainer(trainerId)
    showFeedback(`已停用教練 ${displayName}`)
    suspendModal.value = false
    if (await syncSelfAndKeepAccess(email)) fetchList()
  } catch (e) {
    suspendError.value = e.message
    if (e.status === 409) {
      // 409＝仍有未完成業務。正式停用的 409 只有一段含數量的字串（非結構化），
      // 不解析它——改重新預檢取得最新結構化 blockers 逐項呈現，
      // 這也涵蓋「預檢通過後、正式停用前才新增業務」的競態。
      await runCheck()
    } else if (!isInlineError(e)) {
      // 404（已被別的管理員停用）／500／逾時等：依原錯誤類型呈現，
      // 不再多打一次 suspension-check 蓋掉原始問題。
      notifyApiError(e)
    }
  } finally {
    suspendBusy.value = false
  }
}

// ---- 恢復 ----
const restoreModal = ref(false)
const restoreTarget = ref(null)
const restoreBusy = ref(false)
const restoreError = ref('')

function openRestore(row) {
  restoreTarget.value = row
  restoreError.value = ''
  restoreModal.value = true
}

async function confirmRestore() {
  restoreBusy.value = true
  restoreError.value = ''
  const { trainerId, email, displayName } = restoreTarget.value
  try {
    await restoreTrainer(trainerId)
    showFeedback(`已恢復教練 ${displayName}（狀態為暫停服務）`)
    restoreModal.value = false
    if (await syncSelfAndKeepAccess(email)) fetchList()
  } catch (e) {
    if (isInlineError(e)) restoreError.value = e.message
    else notifyApiError(e)
  } finally {
    restoreBusy.value = false
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container staff-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">教練管理</h1>
    <p class="section-desc">
      查詢與代改教練專業資料、切換可服務狀態、停用或恢復教練。建立新教練請至員工管理將員工升級為教練。
    </p>

    <StaffManagementToolbar
      v-model:keyword="keyword"
      keyword-placeholder="姓名／Email／專長"
      :total="totalItems"
      total-unit="位教練"
      @reset="resetFilters"
    >
      <template #filters>
        <label class="toolbar__field">
          <span class="toolbar__label">範圍</span>
          <BaseSelect v-model="scope" :options="SCOPE_OPTIONS" />
        </label>
        <label class="toolbar__field">
          <span class="toolbar__label">可服務狀態</span>
          <BaseSelect v-model="statusFilter" :options="STATUS_OPTIONS" />
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
            <th>教練</th>
            <th>員工</th>
            <th>姓名</th>
            <th>Email</th>
            <th>專長</th>
            <th>狀態</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.trainerId">
            <td>#{{ r.trainerId }}</td>
            <td>#{{ r.employeeId }}</td>
            <td>
              {{ r.displayName }}
              <span v-if="isSelf(r.email)" class="self-tag">你</span>
            </td>
            <td class="col-email">{{ r.email }}</td>
            <td class="col-specialty">{{ r.specialty || '—' }}</td>
            <td>
              <StaffStatusBadge
                kind="trainerStatus"
                :value="r.deletedAt ? 'DELETED' : r.status"
              />
            </td>
            <td class="col-actions">
              <button type="button" class="btn btn-sm" @click="openDetail(r.trainerId)">
                {{ r.deletedAt ? '詳細' : '詳細／編輯' }}
              </button>
              <template v-if="!r.deletedAt">
                <button type="button" class="btn btn-sm" @click="openStatusToggle(r)">
                  {{ r.status === 'ACTIVE' ? '設為暫停服務' : '設為可服務' }}
                </button>
                <button type="button" class="btn btn-sm btn-danger" @click="openSuspend(r)">停用</button>
              </template>
              <button
                v-else
                type="button"
                class="btn btn-sm btn-accent"
                @click="openRestore(r)"
              >
                恢復
              </button>
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

    <!-- 詳細 / 編輯 -->
    <AppModal
      v-model:visible="editModal"
      :title="isDeletedDetail ? '教練詳細（已停用）' : '教練詳細／編輯'"
      icon=""
      size="lg"
    >
      <div v-if="editLoading" class="spinner"></div>
      <div v-else-if="detail">
        <dl class="modal-readonly">
          <div><dt>教練編號</dt><dd>#{{ detail.trainerId }}</dd></div>
          <div><dt>員工編號</dt><dd>#{{ detail.employeeId }}</dd></div>
          <div><dt>姓名</dt><dd>{{ detail.displayName }}</dd></div>
          <div><dt>Email</dt><dd>{{ detail.email }}</dd></div>
          <div>
            <dt>可服務狀態</dt>
            <dd>
              <StaffStatusBadge
                kind="trainerStatus"
                :value="detail.deletedAt ? 'DELETED' : detail.status"
              />
            </dd>
          </div>
          <div>
            <dt>所屬員工狀態</dt>
            <dd><StaffStatusBadge kind="employeeStatus" :value="detail.employeeStatus" /></dd>
          </div>
        </dl>

        <p v-if="isDeletedDetail" class="modal-form__note">
          此教練已停用，無法編輯專業資料。需先恢復（狀態會回到「暫停服務」）後才能修改。
        </p>

        <form v-else id="trainerEditForm" class="modal-form" @submit.prevent="onSaveEdit">
          <div class="form-group">
            <label class="form-label" for="te-specialty">專長</label>
            <input id="te-specialty" v-model="editForm.specialty" class="form-input" maxlength="255" required />
          </div>
          <div class="form-group">
            <label class="form-label" for="te-experience">經歷</label>
            <textarea id="te-experience" v-model="editForm.experience" class="form-input" rows="3" maxlength="1000" required></textarea>
          </div>
          <div class="form-group">
            <label class="form-label" for="te-bio">自我介紹</label>
            <textarea id="te-bio" v-model="editForm.bio" class="form-input" rows="5" maxlength="5000" required></textarea>
          </div>
          <div class="form-group">
            <label class="form-label" for="te-image">個人圖片網址（選填）</label>
            <input id="te-image" v-model="editForm.profileImageUrl" class="form-input" type="url" maxlength="255" placeholder="https://..." />
            <p class="modal-form__note">目前不提供上傳，請貼入圖片網址。</p>
          </div>
          <OperationMessage :message="editError" tone="error" />
        </form>
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="editModal = false">關閉</button>
        <button
          v-if="!isDeletedDetail"
          type="submit"
          form="trainerEditForm"
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
      title="確認修改教練資料"
      intro="請確認以下變更，送出後將更新此教練對外顯示的專業資料。"
      :changes="editChanges"
      :saving="saving"
      :error-message="confirmError"
      @confirm="confirmEditSave"
    />

    <!-- 可服務狀態切換 -->
    <AppModal v-model:visible="statusModal" title="切換可服務狀態" icon="" size="sm">
      <div v-if="statusTarget">
        <p class="confirm-text">
          將
          <strong>{{ statusTarget.displayName }}</strong>
          由
          <StaffStatusBadge kind="trainerStatus" :value="statusTarget.status" />
          設為
          <StaffStatusBadge kind="trainerStatus" :value="nextStatus" />
          ？
        </p>
        <p v-if="nextStatus === 'UNAVAILABLE'" class="confirm-note">
          暫停服務後，此教練不會出現在可預約清單；已存在的排班／預約／課程不會自動取消。
        </p>
        <p v-else class="confirm-note">
          設為可服務後，會員即可預約此教練。若所屬員工非在職狀態會被後端擋下。
        </p>
        <OperationMessage :message="statusError" tone="error" />
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="statusModal = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="statusBusy" @click="confirmStatusToggle">
          {{ statusBusy ? '處理中…' : `設為${nextStatusLabel}` }}
        </button>
      </template>
    </AppModal>

    <!-- 停用（跨模組阻擋預檢） -->
    <AppModal v-model:visible="suspendModal" title="停用教練" icon="" size="md">
      <div v-if="suspendTarget">
        <p class="confirm-text">
          停用 <strong>{{ suspendTarget.displayName }}</strong>（{{ suspendTarget.email }}）
        </p>

        <div v-if="checkLoading" class="spinner"></div>

        <template v-else>
          <p class="check-caption">停用前跨模組檢查：</p>
          <ul class="blocker-list">
            <li
              v-for="b in blockerList"
              :key="b.key"
              class="blocker-item"
              :class="{ 'blocker-item--hit': b.count > 0 }"
            >
              <div class="blocker-item__head">
                <span class="blocker-item__label">{{ b.label }}</span>
                <span class="blocker-item__count">{{ b.count }}</span>
              </div>
              <p v-if="b.count > 0" class="blocker-item__hint">{{ b.hint }}</p>
            </li>
          </ul>
          <p v-if="!checkResult?.blockers" class="confirm-note confirm-note--warn">
            無法取得跨模組檢查結果，請關閉後重試。
          </p>

          <div v-if="canSuspend" class="suspend-consequence">
            <p class="confirm-note">停用（軟刪除）後：</p>
            <ul class="consequence-list">
              <li>此教練將無法被安排新的排班、私教預約、私教課方案與團課場次。</li>
              <li>教練身分（ROLE_TRAINER）會失效；一般員工身分不受影響。</li>
              <li>歷史資料不會刪除，既有已排定的課程也不受影響。</li>
              <li>恢復後狀態固定為「暫停服務」，不會自動回到可服務。</li>
              <li>正式停用會在交易內重新檢查，若此刻有新業務產生仍可能失敗。</li>
            </ul>
          </div>
          <p v-else-if="hasBlocker" class="confirm-note confirm-note--warn">
            上述有數量的項目需先於各對應模組結清，才能停用此教練。
          </p>
        </template>

        <OperationMessage :message="suspendError" tone="error" />
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="suspendModal = false">取消</button>
        <button type="button" class="btn btn-outline" :disabled="checkLoading" @click="runCheck">
          重新檢查
        </button>
        <button
          type="button"
          class="btn btn-danger"
          :disabled="suspendBusy || checkLoading || !canSuspend"
          @click="confirmSuspend"
        >
          {{ suspendBusy ? '停用中…' : '確定停用' }}
        </button>
      </template>
    </AppModal>

    <!-- 恢復 -->
    <AppModal v-model:visible="restoreModal" title="恢復教練" icon="" size="sm">
      <div v-if="restoreTarget">
        <p class="confirm-text">
          恢復 <strong>{{ restoreTarget.displayName }}</strong>（{{ restoreTarget.email }}）？
        </p>
        <p class="confirm-note">
          恢復後教練身分重新生效，狀態固定為「暫停服務」，需另外切換為「可服務」才會開放預約。
          若所屬員工目前非在職狀態，後端會擋下恢復。
        </p>
        <OperationMessage :message="restoreError" tone="error" />
      </div>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="restoreModal = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="restoreBusy" @click="confirmRestore">
          {{ restoreBusy ? '處理中…' : '確定恢復' }}
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
  max-width: 14rem;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-specialty {
  max-width: 14rem;
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

.modal-readonly {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 0.75rem 1.25rem;
  margin: 0 0 1rem;
  padding: 0.9rem 1rem;
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
}

.modal-readonly div {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
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

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
}

.modal-form__note {
  margin: 0.3rem 0 0;
  font-size: 0.8rem;
  color: var(--text-secondary);
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

.check-caption {
  margin: 0 0 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.blocker-list {
  list-style: none;
  margin: 0 0 1rem;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}

.blocker-item {
  padding: 0.6rem 0.85rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
}

.blocker-item--hit {
  border-color: #e6b8ae;
  background: #fbeeeb;
}

.blocker-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.blocker-item__label {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
}

.blocker-item__count {
  min-width: 1.8rem;
  padding: 0.05rem 0.5rem;
  border-radius: 999px;
  background: #edeae5;
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 700;
  text-align: center;
}

.blocker-item--hit .blocker-item__count {
  background: #cf3b2c;
  color: #fff;
}

.blocker-item__hint {
  margin: 0.4rem 0 0;
  font-size: 0.8rem;
  color: #9b2c1e;
}

.suspend-consequence {
  margin-top: 0.5rem;
}

.consequence-list {
  margin: 0.25rem 0 0;
  padding-left: 1.25rem;
  font-size: 0.83rem;
  color: var(--text-secondary);
  line-height: 1.7;
}
</style>
