<script setup>
import { ref, computed, onMounted } from 'vue'
import { getAllCourses, createCourse, deactivateCourse } from '@/api/adminGroupCourse.js'
import { getTrainers } from '@/api/coachBooking.js'
import { getCourseCategories } from '@/api/product.js'

// 課程狀態對照表：後端回傳 ACTIVE/INACTIVE，畫面顯示中文與顏色
const STATUS_MAP = {
  ACTIVE: { label: '上架中', className: 'status-available' },
  INACTIVE: { label: '已下架', className: 'status-cancelled' },
}

const courses = ref([])
const coursesLoading = ref(true)

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

function statusInfo(status) {
  return STATUS_MAP[status] || { label: status, className: '' }
}

// 表單下方的專長提示要用，從已經抓到的教練清單反查目前選中的那一位
const selectedTrainer = computed(
  () => trainers.value.find((trainer) => trainer.trainerId === form.value.trainerId) ?? null,
)

async function fetchCourses() {
  coursesLoading.value = true
  try {
    const res = await getAllCourses()
    courses.value = res.data
  } finally {
    coursesLoading.value = false
  }
}

async function fetchTrainers() {
  trainersLoading.value = true
  try {
    const res = await getTrainers()
    trainers.value = res.data
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
    // 依 API 規格，伺服器端錯誤（驗證失敗、查無教練/分類等）用 alert 顯示，
    // 跟表單本身的必填檢查（formError）分開處理
    alert(err.response?.data?.message || '建立失敗，請稍後再試')
  } finally {
    submitting.value = false
  }
}

// 點擊「下架」：先做二次確認，確定後才呼叫下架 API；成功後刷新列表讓狀態變成「已下架」
async function handleDeactivate(course) {
  const confirmed = window.confirm(`確定要下架「${course.name}」嗎？之後將無法用這個課程建立新場次`)
  if (!confirmed) return

  try {
    await deactivateCourse(course.id)
    await fetchCourses()
  } catch (err) {
    alert(err.response?.data?.message || '下架失敗，請稍後再試')
  }
}

onMounted(() => {
  fetchCourses()
  fetchTrainers()
  fetchCategories()
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
            {{ trainer.name }}（{{ trainer.specialty }}）
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

    <div v-if="coursesLoading" class="spinner"></div>

    <template v-else>
      <p v-if="courses.length === 0" class="empty-state">目前還沒有任何團課主檔，先建立第一筆看看。</p>

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
              <button
                v-if="course.status === 'ACTIVE'"
                class="btn btn-outline"
                type="button"
                @click="handleDeactivate(course)"
              >
                下架
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
