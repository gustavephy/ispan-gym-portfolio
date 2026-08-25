<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import {
  getMembers,
  getMemberById,
  updateMember,
  suspendMember,
  activateMember,
} from '@/api/adminMember.js'
import { getMemberQuotas } from '@/api/memberCourseQuota.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import { useFeedback } from '@/composables/useFeedback.js'
import OperationMessage from '@/components/OperationMessage.vue'
import FeedbackToast from '@/components/FeedbackToast.vue'
import Pagination from '@/components/Pagination.vue'

// 後端 UserStatus 只有這三個值；PENDING 代表 Email 尚未驗證，
// 停權／恢復都會被後端擋下（409），因此該列不顯示這兩個按鈕
const STATUS_MAP = {
  ACTIVE: { label: '正常', className: 'status-active' },
  SUSPENDED: { label: '已停權', className: 'status-suspended' },
  PENDING: { label: '待驗證', className: 'status-pending' },
}

// 與後端 AdminMemberService.ALLOWED_SORTS 一致；傳其他值後端會靜默退回 id
const SORT_OPTIONS = [
  { value: 'id', label: '會員編號' },
  { value: 'name', label: '姓名' },
  { value: 'createdAt', label: '註冊時間' },
]

const members = ref([])
const listLoading = ref(true)
const listError = ref(null)

const page = ref(0)
const size = ref(10)
const totalItems = ref(0)
const totalPages = ref(0)

const keyword = ref('')
const statusFilter = ref('')
const sort = ref('id')
const direction = ref('asc')

let searchDebounceTimer = null

// 編輯抽屜：null 代表關閉；開啟時才載入完整資料（列表刻意不含地址等 PII）
const editing = ref(null)
const editLoading = ref(false)
const memberQuotas = ref([])
const quotasLoading = ref(false)
const formError = ref(null)
const saving = ref(false)

const { feedback, showFeedback } = useFeedback()

const emptyStateMessage = computed(() =>
  keyword.value || statusFilter.value
    ? '查無符合條件的會員'
    : '目前還沒有任何會員資料。',
)

function statusInfo(status) {
  return STATUS_MAP[status] ?? { label: status, className: '' }
}

function formatDateTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(
    d.getDate(),
  ).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function fetchMembers() {
  listLoading.value = true
  listError.value = null
  try {
    const { data } = await getMembers({
      keyword: keyword.value,
      status: statusFilter.value,
      page: page.value,
      size: size.value,
      sort: sort.value,
      direction: direction.value,
    })
    // 後端回的是 Spring Page：content／totalElements／totalPages
    members.value = data.content
    totalItems.value = data.totalElements
    totalPages.value = data.totalPages
  } catch (e) {
    listError.value = e.message
  } finally {
    listLoading.value = false
  }
}

// 搜尋字串 debounce 400ms，避免每敲一個字就打一次 API；
// 換條件代表結果集變了，page 一律重設為 0，免得卡在不存在的頁碼
watch(keyword, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchMembers()
  }, 400)
})

watch([statusFilter, sort, direction], () => {
  page.value = 0
  fetchMembers()
})

function handlePageChange(newPage) {
  page.value = newPage
  fetchMembers()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchMembers()
}

async function openEdit(memberId) {
  editing.value = { memberId }
  editLoading.value = true
  formError.value = null
  try {
    const { data } = await getMemberById(memberId)
    editing.value = {
      memberId: data.memberId,
      email: data.email,
      status: data.status,
      firstName: data.firstName ?? '',
      lastName: data.lastName ?? '',
      phone: data.phone ?? '',
      dob: data.dob ?? '',
      emergencyContact: data.emergencyContact ?? '',
      emergencyPhone: data.emergencyPhone ?? '',
      zipCode: data.zipCode ?? '',
      city: data.city ?? '',
      district: data.district ?? '',
      address: data.address ?? '',
    }

    quotasLoading.value = true
    try {
      const quotaRes = await getMemberQuotas(memberId)
      // 後端回傳 List（不分頁），trainer 是巢狀物件（可能為 null）
      memberQuotas.value = quotaRes.data
    } catch (quotaError) {
      // 額度查詢失敗只記錄，不影響會員資料編輯功能本身
      console.error('查詢會員額度失敗', quotaError)
      memberQuotas.value = []
    } finally {
      quotasLoading.value = false
    }
  } catch (e) {
    editing.value = null
    notifyApiError(e)
  } finally {
    editLoading.value = false
  }
}

function closeEdit() {
  editing.value = null
  formError.value = null
  memberQuotas.value = []
}

async function onSave() {
  if (!editing.value) return
  saving.value = true
  formError.value = null
  try {
    const m = editing.value
    await updateMember(m.memberId, {
      firstName: m.firstName,
      lastName: m.lastName,
      phone: m.phone,
      // 空字串會讓後端的 @Past／型別轉換失敗，一律轉回 null 代表不填
      dob: m.dob || null,
      emergencyContact: m.emergencyContact || null,
      emergencyPhone: m.emergencyPhone || null,
      zipCode: m.zipCode || null,
      city: m.city || null,
      district: m.district || null,
      address: m.address || null,
    })
    showFeedback('會員資料已更新')
    closeEdit()
    fetchMembers()
  } catch (e) {
    // 驗證錯誤留在表單內顯示，其餘交給全域提示（比照 AdminGroupCourseView）
    if (isInlineError(e)) {
      formError.value = e.message
    } else {
      notifyApiError(e)
    }
  } finally {
    saving.value = false
  }
}

// 停權會讓該會員無法登入，是有感操作，比照既有慣例先二次確認
async function onSuspend(member) {
  if (!window.confirm(`確定要停權「${member.displayName}」嗎？停權後該會員將無法登入。`)) {
    return
  }
  try {
    await suspendMember(member.memberId)
    showFeedback(`已停權 ${member.displayName}`)
    fetchMembers()
  } catch (e) {
    notifyApiError(e)
  }
}

async function onActivate(member) {
  try {
    await activateMember(member.memberId)
    showFeedback(`已恢復 ${member.displayName}`)
    fetchMembers()
  } catch (e) {
    notifyApiError(e)
  }
}

onMounted(fetchMembers)
</script>

<template>
  <div class="page-container admin-member-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">會員資料管理</h1>
    <p class="section-desc">查詢、代改會員資料，並管理會員的啟用狀態。</p>

    <!-- 篩選列 -->
    <div class="filters card">
      <label class="filter filter--search">
        <span class="filter__label">搜尋</span>
        <input
          v-model="keyword"
          type="search"
          class="filter__input"
          placeholder="姓名／電話／Email"
        />
      </label>

      <label class="filter">
        <span class="filter__label">狀態</span>
        <select v-model="statusFilter" class="filter__input">
          <option value="">全部</option>
          <option value="ACTIVE">正常</option>
          <option value="SUSPENDED">已停權</option>
          <option value="PENDING">待驗證</option>
        </select>
      </label>

      <label class="filter">
        <span class="filter__label">排序</span>
        <select v-model="sort" class="filter__input">
          <option v-for="opt in SORT_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </label>

      <label class="filter">
        <span class="filter__label">順序</span>
        <select v-model="direction" class="filter__input">
          <option value="asc">遞增</option>
          <option value="desc">遞減</option>
        </select>
      </label>

      <span class="filters__total">共 {{ totalItems }} 位會員</span>
    </div>

    <OperationMessage :message="listError" />

    <div class="card table-card">
      <div v-if="listLoading" class="spinner"></div>
      <p v-else-if="members.length === 0" class="empty-hint">{{ emptyStateMessage }}</p>
      <table v-else class="data-table">
        <thead>
          <tr>
            <th>編號</th>
            <th>姓名</th>
            <th>Email</th>
            <th>電話</th>
            <th>狀態</th>
            <th>註冊時間</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in members" :key="m.memberId">
            <td>{{ m.memberId }}</td>
            <td>{{ m.displayName }}</td>
            <td class="col-email">{{ m.email }}</td>
            <td>{{ m.phone || '—' }}</td>
            <td>
              <span class="status-badge" :class="statusInfo(m.status).className">
                {{ statusInfo(m.status).label }}
              </span>
            </td>
            <td>{{ formatDateTime(m.createdAt) }}</td>
            <td class="col-actions">
              <button type="button" class="btn btn-sm" @click="openEdit(m.memberId)">編輯</button>
              <!-- PENDING 尚未完成驗證，停權／恢復都會被後端擋下，故不顯示按鈕 -->
              <button
                v-if="m.status === 'ACTIVE'"
                type="button"
                class="btn btn-sm btn-danger"
                @click="onSuspend(m)"
              >
                停權
              </button>
              <button
                v-else-if="m.status === 'SUSPENDED'"
                type="button"
                class="btn btn-sm btn-accent"
                @click="onActivate(m)"
              >
                恢復
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <Pagination
        v-if="totalPages > 0"
        :page="page"
        :size="size"
        :total-pages="totalPages"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
      />
    </div>

    <!-- 編輯面板 -->
    <div v-if="editing" class="edit-overlay" @click.self="closeEdit">
      <section class="edit-panel card">
        <header class="edit-panel__header">
          <h2>編輯會員 #{{ editing.memberId }}</h2>
          <button type="button" class="edit-panel__close" @click="closeEdit">×</button>
        </header>

        <div v-if="editLoading" class="spinner"></div>
        <form v-else class="edit-form" @submit.prevent="onSave">
          <p class="edit-form__readonly">
            <span>Email</span><strong>{{ editing.email }}</strong>
          </p>

          <div class="edit-form__row">
            <label>
              <span>姓氏 *</span>
              <input v-model="editing.lastName" type="text" maxlength="50" required />
            </label>
            <label>
              <span>名字 *</span>
              <input v-model="editing.firstName" type="text" maxlength="50" required />
            </label>
          </div>

          <div class="edit-form__row">
            <label>
              <span>電話 *</span>
              <input
                v-model="editing.phone"
                type="tel"
                required
                pattern="^09\d{8}$"
                placeholder="0912345678"
                title="請輸入台灣手機號碼，例如 0912345678"
              />
            </label>
            <label>
              <span>生日</span>
              <input v-model="editing.dob" type="date" />
            </label>
          </div>

          <div class="edit-form__row">
            <label>
              <span>緊急聯絡人</span>
              <input v-model="editing.emergencyContact" type="text" maxlength="50" />
            </label>
            <label>
              <span>緊急聯絡電話</span>
              <input v-model="editing.emergencyPhone" type="tel" maxlength="20" />
            </label>
          </div>

          <div class="edit-form__row">
            <label>
              <span>郵遞區號</span>
              <input v-model="editing.zipCode" type="text" maxlength="10" />
            </label>
            <label>
              <span>縣市</span>
              <input v-model="editing.city" type="text" maxlength="50" />
            </label>
            <label>
              <span>鄉鎮市區</span>
              <input v-model="editing.district" type="text" maxlength="50" />
            </label>
          </div>

          <label class="edit-form__full">
            <span>詳細地址</span>
            <input v-model="editing.address" type="text" maxlength="255" />
          </label>

          <OperationMessage :message="formError" />

          <div class="edit-form__actions">
            <button type="button" class="btn" @click="closeEdit">取消</button>
            <button type="submit" class="btn btn-accent" :disabled="saving">
              {{ saving ? '儲存中…' : '儲存' }}
            </button>
          </div>
        </form>

        <section class="quota-section">
          <h3>私教課額度</h3>
          <div v-if="quotasLoading" class="spinner"></div>
          <p v-else-if="memberQuotas.length === 0" class="quota-empty">
            此會員目前沒有任何私教課額度紀錄
          </p>
          <table v-else class="quota-table">
            <thead>
              <tr>
                <th>課程</th>
                <th>教練</th>
                <th>剩餘</th>
                <th>預約中</th>
                <th>已用</th>
                <th>到期日</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="q in memberQuotas" :key="q.id">
                <td>{{ q.courseName }}</td>
                <td>{{ q.trainer ? `${q.trainer.lastName ?? ''}${q.trainer.firstName ?? ''}` : '—' }}</td>
                <td>{{ q.remainingCourse }}</td>
                <td>{{ q.reservedCourse }}</td>
                <td>{{ q.usedCourse }}</td>
                <td>{{ q.expiryDate ? formatDateTime(q.expiryDate) : '無期限' }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </section>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.admin-member-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

/* ---- 篩選列 ---- */
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 1.5rem;
  padding: 1.5rem 1.75rem;
  margin-bottom: 1.5rem;
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
  /* 搜尋框給大一點、下拉選單不必跟著寬（見 .filter--search） */
  min-width: 8rem;
}

.filter--search .filter__input {
  min-width: 14rem;
}

.filters__total {
  margin-left: auto;
  font-size: 0.9rem;
  color: var(--text-secondary);
}

/* ---- 表格 ---- */
.table-card {
  padding: 1.5rem 1.75rem;
  /* 欄位多，窄螢幕讓表格自己橫向捲動，不讓整頁破版 */
  overflow-x: auto;
}

/* 表頭四角自己收圓；卡片有內距後表頭不再貼齊卡片邊，圓角要由表頭自己處理 */
.data-table thead tr:first-child th:first-child {
  border-top-left-radius: var(--radius-sm);
  border-bottom-left-radius: var(--radius-sm);
}
.data-table thead tr:first-child th:last-child {
  border-top-right-radius: var(--radius-sm);
  border-bottom-right-radius: var(--radius-sm);
}

.data-table {
  width: 100%;
  /* separate + border-spacing:0 而非 collapse：collapse 下表頭的圓角會被邊框合併吃掉 */
  border-collapse: separate;
  border-spacing: 0;
  font-size: 0.95rem;
}

.data-table th,
.data-table td {
  /* 左右由卡片內距負責，儲存格只留欄間距，首末欄才不會離卡片邊太遠 */
  padding: 0.9rem 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

/* 首末欄貼齊卡片內距的邊界，不再多一層縮排 */
.data-table th:first-child,
.data-table td:first-child {
  padding-left: 0.85rem;
}
.data-table th:last-child,
.data-table td:last-child {
  padding-right: 0.85rem;
}

/* 表頭加底色與列內容分層，長列表往下看時比較不會迷失 */
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
  gap: 0.6rem;
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 700;
}

.status-active {
  background: #e3f4e6;
  color: #256b33;
}

.status-suspended {
  background: #fae3e0;
  color: #9b2c1e;
}

.status-pending {
  background: #fbf1d9;
  color: #8a6410;
}

.btn-sm {
  padding: 0.4rem 0.9rem;
  font-size: 0.85rem;
  white-space: nowrap;
}

.btn-danger {
  background: #cf3b2c;
  color: #fff;
  border-color: #cf3b2c;
}

/* ---- 編輯面板 ---- */
.edit-overlay {
  position: fixed;
  inset: 0;
  background: rgba(30, 24, 20, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 200;
}

.edit-panel {
  width: min(46rem, 100%);
  /* 欄位多，小螢幕時面板本身可捲動，不會超出視窗 */
  max-height: 88vh;
  overflow-y: auto;
  padding: 1.25rem 1.5rem 1.5rem;
}

.edit-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.edit-panel__header h2 {
  margin: 0;
  font-size: 1.15rem;
}

.edit-panel__close {
  background: none;
  border: none;
  font-size: 1.6rem;
  line-height: 1;
  cursor: pointer;
  color: var(--text-secondary);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
}

.edit-form__readonly {
  margin: 0;
  padding: 0.5rem 0.75rem;
  background: var(--bg-page, #faf7f2);
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
}

.edit-form__readonly span {
  color: var(--text-secondary);
  margin-right: 0.6rem;
}

.edit-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.9rem;
}

.edit-form__row label,
.edit-form__full {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  flex: 1 1 12rem;
}

.edit-form label span {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-secondary);
}

.edit-form input {
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
}

.edit-form__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
  margin-top: 0.4rem;
}

.quota-section {
  margin-top: 1.5rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--border-color, #ddd);
}
.quota-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}
.quota-table th,
.quota-table td {
  padding: 0.5rem;
  text-align: left;
  border-bottom: 1px solid var(--border-color, #eee);
}
.quota-empty {
  color: var(--text-secondary, #666);
  padding: 1rem 0;
}
</style>
