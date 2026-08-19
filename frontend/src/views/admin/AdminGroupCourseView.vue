<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { getAllCourses, createCourse, updateCourse, deactivateCourse, activateCourse } from '@/api/adminGroupCourse.js'
import { getTrainers } from '@/api/coachBooking.js'
import { getCourseCategories } from '@/api/product.js'
import { isInlineError } from '@/api/api-error.js'
import { notifyApiError } from '@/api/error-bus.js'
import OperationMessage from '@/components/OperationMessage.vue'
import Pagination from '@/components/Pagination.vue'

// 課程狀態對照表：後端回傳 ACTIVE/INACTIVE，畫面顯示中文與顏色
const STATUS_MAP = {
  ACTIVE: { label: '上架中', className: 'status-available' },
  INACTIVE: { label: '已下架', className: 'status-cancelled' },
}

const courses = ref([])
const coursesLoading = ref(true)

// 分頁與搜尋狀態
const page = ref(0)
const size = ref(10)
const search = ref('')
const totalItems = ref(0)
const totalPages = ref(0)

let searchDebounceTimer = null

const trainers = ref([])
const trainersLoading = ref(true)

const categories = ref([])
const categoriesLoading = ref(true)

const form = ref({
  name: '',
  trainerId: '',
  description: '',
  maxCapacity: 15,
  courseCategoryId: '',
  isTrial: false,
})
const formError = ref('')
const submitting = ref(false)

// 下架操作的就地錯誤訊息，顯示在課程列表上方
const courseListMessage = ref('')

// 編輯課程用的 modal 狀態，跟最上面的建立表單完全分開，互不影響
const isEditModalOpen = ref(false)
const editingCourseId = ref(null)
const editForm = ref({
  name: '',
  description: '',
  imageUrl: '',
  maxCapacity: 15,
  courseCategoryId: '',
  isTrial: false,
})
// 授課教練唯讀顯示用，不放進 editForm——這支修改 API 不接受變更教練
const editTrainerName = ref('')
const editFormError = ref('')
const editSubmitting = ref(false)

function statusInfo(status) {
  return STATUS_MAP[status] || { label: status, className: '' }
}

// 有下搜尋關鍵字時，「查無資料」的訊息要跟「根本還沒有任何課程」分開講，避免使用者誤會
const emptyStateMessage = computed(() =>
  search.value ? '查無符合搜尋條件的課程' : '目前還沒有任何團課主檔，先建立第一筆看看。',
)

// 表單下方的專長提示要用，從已經抓到的教練清單反查目前選中的那一位
const selectedTrainer = computed(
  () => trainers.value.find((trainer) => trainer.trainerId === form.value.trainerId) ?? null,
)

async function fetchCourses() {
  coursesLoading.value = true
  try {
    const res = await getAllCourses({ page: page.value, size: size.value, search: search.value })
    courses.value = res.data.items
    totalItems.value = res.data.totalItems
    totalPages.value = res.data.totalPages
  } finally {
    coursesLoading.value = false
  }
}

// 搜尋字串變動時 debounce 400ms 才觸發查詢，避免每敲一個字就打一次 API；
// 換了搜尋條件代表結果集變了，page 一律重設為 0，避免卡在一個搜尋後根本不存在的頁碼
watch(search, () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 0
    fetchCourses()
  }, 400)
})

// Pagination 元件的 update:page／update:size 事件都走這裡；換頁碼大小時同樣要把
// page 重設為 0，理由同上——待在原頁碼可能會超出新的每頁筆數下的總頁數範圍
function handlePageChange(newPage) {
  page.value = newPage
  fetchCourses()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 0
  fetchCourses()
}

async function fetchTrainers() {
  trainersLoading.value = true
  try {
    const res = await getTrainers()
    // 新 API 會回傳 UNAVAILABLE 教練，這裡過濾只留 ACTIVE，避免把課排給已停用的教練
    trainers.value = res.data.filter((trainer) => trainer.status === 'ACTIVE')
  } finally {
    trainersLoading.value = false
  }
}

async function fetchCategories() {
  categoriesLoading.value = true
  try {
    const res = await getCourseCategories()
    categories.value = res.data
  } finally {
    categoriesLoading.value = false
  }
}

async function handleCreate() {
  formError.value = ''

  if (!form.value.name || !form.value.trainerId || !form.value.maxCapacity) {
    formError.value = '請填寫課程名稱、授課教練與人數上限'
    return
  }

  submitting.value = true
  try {
    await createCourse({
      name: form.value.name,
      trainerId: form.value.trainerId,
      description: form.value.description || null,
      isTrial: form.value.isTrial,
      maxCapacity: form.value.maxCapacity,
      courseCategoryId: form.value.courseCategoryId || null,
    })
    form.value = {
      name: '',
      trainerId: '',
      description: '',
      maxCapacity: 15,
      courseCategoryId: '',
      isTrial: false,
    }
    await fetchCourses()
  } catch (err) {
    // 依新的錯誤處理慣例：使用者操作結果（如驗證失敗、查無教練/分類）就地顯示在表單旁，
    // 系統層級錯誤交給全域提示條，跟表單本身的必填檢查（formError）分開處理
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

// 點擊「下架」：先做二次確認，確定後才呼叫下架 API；成功後刷新列表讓狀態變成「已下架」
async function handleDeactivate(course) {
  const confirmed = window.confirm(`確定要下架「${course.name}」嗎？之後將無法用這個課程建立新場次`)
  if (!confirmed) return

  courseListMessage.value = ''
  try {
    await deactivateCourse(course.id)
    await fetchCourses()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      courseListMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點擊「重新上架」：不需二次確認（可逆操作，風險低於下架），成功後刷新列表讓狀態變成「上架中」
async function handleActivate(course) {
  courseListMessage.value = ''
  try {
    await activateCourse(course.id)
    await fetchCourses()
  } catch (err) {
    const apiError = err
    if (isInlineError(apiError)) {
      courseListMessage.value = apiError.message
    } else {
      notifyApiError(apiError)
    }
  }
}

// 點擊「編輯」：開啟 modal，並把這筆課程既有的內容帶入編輯表單；授課教練唯讀顯示，不可編輯
function openEditModal(course) {
  editFormError.value = ''
  editForm.value = {
    name: course.name,
    description: course.description || '',
    imageUrl: course.imageUrl || '',
    maxCapacity: course.maxCapacity,
    courseCategoryId: course.courseCategoryId ?? '',
    isTrial: course.isTrial,
  }
  editTrainerName.value = course.trainerName
  editingCourseId.value = course.id
  isEditModalOpen.value = true
}

// 取消／點遮罩都走這裡：關閉 modal，不送出任何請求
function closeEditModal() {
  isEditModalOpen.value = false
  editingCourseId.value = null
  editFormError.value = ''
}

async function handleEditSubmit() {
  editFormError.value = ''

  if (!editForm.value.name || !editForm.value.maxCapacity) {
    editFormError.value = '請填寫課程名稱與人數上限'
    return
  }
  if (editForm.value.maxCapacity < 1) {
    editFormError.value = '人數上限至少為 1'
    return
  }

  editSubmitting.value = true
  try {
    await updateCourse(editingCourseId.value, {
      name: editForm.value.name,
      description: editForm.value.description || null,
      imageUrl: editForm.value.imageUrl || null,
      isTrial: editForm.value.isTrial,
      maxCapacity: editForm.value.maxCapacity,
      courseCategoryId: editForm.value.courseCategoryId || null,
    })
    closeEditModal()
    await fetchCourses()
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

onMounted(() => {
  fetchCourses()
  fetchTrainers()
  fetchCategories()
})

onUnmounted(() => {
  clearTimeout(searchDebounceTimer)
})
</script>

<template>
  <div class="page-container">
    <span class="section-tag">ADMIN</span>
    <h2 class="section-title">團課主檔管理</h2>
    <p class="section-desc">建立團體課程主檔，並查看目前所有課程的狀態。</p>

    <form class="slot-form" @submit.prevent="handleCreate">
      <div class="field">
        <label for="courseName">課程名稱</label>
        <input id="courseName" v-model="form.name" type="text" required />
      </div>
      <div class="field">
        <label for="courseTrainer">授課教練</label>
        <select id="courseTrainer" v-model="form.trainerId" required :disabled="trainersLoading">
          <option value="" disabled>請選擇</option>
          <option v-for="trainer in trainers" :key="trainer.trainerId" :value="trainer.trainerId">
            {{ trainer.displayName }}（{{ trainer.specialty }}）
          </option>
        </select>
        <p v-if="selectedTrainer" class="field-hint">此教練專長：{{ selectedTrainer.specialty }}</p>
      </div>
      <div class="field">
        <label for="courseMaxCapacity">人數上限</label>
        <input id="courseMaxCapacity" v-model.number="form.maxCapacity" type="number" min="1" required />
      </div>
      <div class="field">
        <label for="courseCategory">課程分類</label>
        <select id="courseCategory" v-model="form.courseCategoryId" :disabled="categoriesLoading">
          <option value="">不分類</option>
          <option
            v-for="category in categories"
            :key="category.courseCategoryId"
            :value="category.courseCategoryId"
          >
            {{ category.name }}
          </option>
        </select>
      </div>
      <div class="field field-checkbox">
        <label for="courseIsTrial">
          <input id="courseIsTrial" v-model="form.isTrial" type="checkbox" />
          是否體驗課
        </label>
      </div>
      <div class="field field-wide">
        <label for="courseDescription">簡介</label>
        <textarea id="courseDescription" v-model="form.description" rows="3"></textarea>
      </div>
      <button class="btn btn-accent" type="submit" :disabled="submitting">
        {{ submitting ? '建立中…' : '建立課程' }}
      </button>
    </form>
    <p v-if="formError" class="form-error">{{ formError }}</p>

    <div class="search-field">
      <input id="courseSearch" v-model="search" type="text" placeholder="搜尋課程名稱或教練姓名" />
    </div>

    <OperationMessage :message="courseListMessage" />

    <div v-if="coursesLoading" class="spinner"></div>

    <template v-else>
      <p v-if="courses.length === 0" class="empty-state">{{ emptyStateMessage }}</p>

      <table v-else class="slot-table">
        <thead>
          <tr>
            <th>課程名稱</th>
            <th>授課教練</th>
            <th>人數上限</th>
            <th>分類</th>
            <th>體驗課</th>
            <th>狀態</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="course in courses" :key="course.id">
            <td>{{ course.name }}</td>
            <td>{{ course.trainerName }}</td>
            <td>{{ course.maxCapacity }}</td>
            <td>{{ course.courseCategoryName || '未分類' }}</td>
            <td>{{ course.isTrial ? '是' : '否' }}</td>
            <td>
              <span class="status-badge" :class="statusInfo(course.status).className">
                {{ statusInfo(course.status).label }}
              </span>
            </td>
            <td>
              <div class="row-actions">
                <button class="btn btn-outline" type="button" @click="openEditModal(course)">編輯</button>
                <button
                  v-if="course.status === 'ACTIVE'"
                  class="btn btn-outline"
                  type="button"
                  @click="handleDeactivate(course)"
                >
                  下架
                </button>
                <button
                  v-if="course.status === 'INACTIVE'"
                  class="btn btn-outline"
                  type="button"
                  @click="handleActivate(course)"
                >
                  重新上架
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
        <h3 class="modal-title">編輯課程</h3>
        <form @submit.prevent="handleEditSubmit">
          <div class="modal-fields">
            <div class="field field-wide">
              <label for="editCourseName">課程名稱</label>
              <input id="editCourseName" v-model="editForm.name" type="text" required />
            </div>
            <div class="field field-wide">
              <label>授課教練</label>
              <p class="field-readonly">{{ editTrainerName }}</p>
              <p class="field-hint">如需更換教練，請下架後另建新課程</p>
            </div>
            <div class="field">
              <label for="editCourseMaxCapacity">人數上限</label>
              <input
                id="editCourseMaxCapacity"
                v-model.number="editForm.maxCapacity"
                type="number"
                min="1"
                required
              />
            </div>
            <div class="field">
              <label for="editCourseCategory">課程分類</label>
              <select id="editCourseCategory" v-model="editForm.courseCategoryId" :disabled="categoriesLoading">
                <option value="">不分類</option>
                <option
                  v-for="category in categories"
                  :key="category.courseCategoryId"
                  :value="category.courseCategoryId"
                >
                  {{ category.name }}
                </option>
              </select>
            </div>
            <div class="field field-checkbox">
              <label for="editCourseIsTrial">
                <input id="editCourseIsTrial" v-model="editForm.isTrial" type="checkbox" />
                是否體驗課
              </label>
            </div>
            <div class="field field-wide">
              <label for="editCourseImageUrl">圖片網址</label>
              <input id="editCourseImageUrl" v-model="editForm.imageUrl" type="text" />
            </div>
            <div class="field field-wide">
              <label for="editCourseDescription">簡介</label>
              <textarea id="editCourseDescription" v-model="editForm.description" rows="3"></textarea>
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

.field-wide {
  flex: 1 1 100%;
}

.field-checkbox {
  justify-content: center;
}

.field-checkbox label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.9rem;
  color: var(--text-secondary);
}

.field label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.field-hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.field input,
.field select,
.field textarea {
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font: inherit;
  color: var(--text-primary);
}

.field textarea {
  resize: vertical;
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

.field-readonly {
  margin: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  color: var(--text-secondary);
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
