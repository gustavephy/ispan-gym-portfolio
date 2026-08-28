<template>
  <div>
    <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
      <button class="btn btn-primary btn-sm" @click="showAddPrivateCourse = !showAddPrivateCourse">
        <span>{{ showAddPrivateCourse ? '✕ 取消新增' : '＋ 新增私教課程' }}</span>
      </button>
    </div>

    <!-- 新增私教課程表單 -->
    <Transition name="slide">
      <AdminFormCard
        v-if="showAddPrivateCourse"
        title="新增私教課程資料"
        helper="*請確實填寫指導教練與課程資訊"
        @submit="createPrivateCourse"
      >
        <div class="form-group" style="grid-column: span 2;">
          <label class="form-label">課程名稱</label>
          <input v-model="privateCourseForm.name" class="form-input" placeholder="例如: 1對1私人教練體驗課" required />
        </div>
        <!-- 指導教練占 2 欄 -->
        <div class="form-group" style="grid-column: span 2;">
          <label class="form-label">指導教練</label>
          <select v-model="privateCourseForm.trainerId" class="form-input" required>
            <option :value="null" disabled>請選擇指導教練</option>
            <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
              {{ t.displayName }} ({{ t.specialty }})
            </option>
          </select>
        </div>
        <!-- 課程分類 + 免費體驗 占 2 欄 -->
        <div class="form-group" style="grid-column: span 2;">
          <label class="form-label">課程分類</label>
          <div style="display:flex; align-items:center; gap: 0.75rem;">
            <select v-model="privateCourseForm.courseCategoryId" class="form-input" required style="flex:1; max-width: 240px;">
              <option :value="null" disabled>請選擇分類</option>
              <option v-for="cat in privateCourseCategories" :key="cat.id" :value="cat.id">
                {{ cat.name }}
              </option>
            </select>
            <label class="checkbox-container" style="white-space:nowrap; flex-shrink:0;">
              <input v-model="privateCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
              <span class="checkmark"></span>
              <span class="checkbox-label-text">免費體驗</span>
            </label>
          </div>
        </div>
        <!-- 堂數 / 售價 / 上限 各占 1 欄，一排三格 -->
        <div class="form-group">
          <label class="form-label">課程堂數</label>
          <input v-model.number="privateCourseForm.sessionPerUnit" type="number" class="form-input" min="1" required />
        </div>
        <div class="form-group">
          <label class="form-label">課程售價</label>
          <input v-model.number="privateCourseForm.price" type="number" class="form-input" min="0" required />
        </div>
        <div class="form-group">
          <label class="form-label">上限人數</label>
          <input v-model.number="privateCourseForm.maxCapacity" type="number" class="form-input" min="1" required />
        </div>

        <template #extra>
          <div class="form-group file-upload-group" style="margin-top: 1rem; width: 100%;">
            <label class="form-label">課程照片</label>
            <div class="file-upload-wrapper" style="display: flex; align-items: center; gap: 1rem; margin-bottom: 0.5rem;">
              <label class="custom-file-btn" style="background: var(--primary, #5a4a42); color: #ffffff; padding: 0.5rem 1rem; border-radius: 6px; cursor: pointer; display: inline-block;">
                上傳照片
                <input type="file" @change="e => uploadFile(e, 'addPrivateCourse')" accept="image/*" style="display: none;" />
              </label>
              <span v-if="uploadingFile" class="loading-spinner-inline"></span>
              <img v-if="privateCourseForm.imageUrl" :src="formatImageUrl(privateCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" style="width: 90px; height: 90px; object-fit: cover; border-radius: 6px; border: 1px solid var(--border, #e2e8f0);" />
            </div>
            <input v-model="privateCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" style="width: 100%; margin-top: 0.5rem;" />
          </div>
          <div class="form-group" style="margin-top: 1rem; width: 100%;">
            <label class="form-label">詳細說明</label>
            <textarea v-model="privateCourseForm.description" class="form-input" rows="4" placeholder="填寫課程特色、適合對象等說明" style="width: 100%; padding: 0.5rem; box-sizing: border-box;"></textarea>
          </div>
        </template>

        <template #actions>
          <button type="submit" class="btn btn-primary" :disabled="saving">確認新增上傳</button>
        </template>
      </AdminFormCard>
    </Transition>

    <!-- 統一搜尋與篩選列 -->
    <SearchFilter
      v-model="pcFilterParams"
      keyword-label="私教課程搜尋"
      keyword-placeholder="搜尋課程名稱、教練姓名..."
      date-label="上架日期範圍"
      @search="onPcSearch"
      @reset="resetPcFilters"
    />

    <!-- 快速狀態篩選 -->
    <CategoryFilter
      v-model="pcActiveFilter"
      label="快速狀態篩選："
      :options="pcCategoryFilters"
    />

    <!-- 私教課程列表 -->
    <AdminTable
      id="pcTable"
      :columns="pcColumns"
      :data="paginatedPrivateCourses"
      rowKey="privateCourseId"
      :totalItems="pcTotalItems"
      :totalPages="pcTotalPages"
      v-model:currentPage="pcCurrentPage"
      v-model:itemsPerPage="pcItemsPerPage"
    >
      <template #id="{ row }">
        #{{ row.id || row.privateCourseId }}
      </template>
      <template #imageUrl="{ row }">
        <img :src="row.imageUrl ? formatImageUrl(row.imageUrl) : DEFAULT_COURSE_IMAGE" class="product-thumb" alt="Course Image"
             @error="useFallbackImage($event, DEFAULT_COURSE_IMAGE)" />
      </template>
      <template #name="{ row }">
        <div class="name-text">{{ row.name }}</div>
        <div class="desc-text" :title="row.description">{{ row.description || '無詳細說明' }}</div>
      </template>
      <template #trainerName="{ row }">
        <div style="font-weight: 600; color: var(--primary-dark);">{{ row.trainerName || row.trainer?.name || '專屬教練' }}</div>
      </template>
      <template #sessionPerUnit="{ row }">
        <span style="font-weight: 600;">{{ row.sessionPerUnit || row.sessionsPerUnit || 1 }} 堂</span><br>
        <span style="font-size:0.82rem; color: var(--text-muted);">上限 {{ row.maxCapacity || 1 }} 人</span>
      </template>
      <template #price="{ value }">
        <span style="font-weight: 700; color: var(--accent);">NT$ {{ Number(value || 0).toLocaleString() }}</span>
      </template>
      <template #status="{ value }">
        <div class="status-cell-wrap" style="display: flex; align-items: center; justify-content: center; gap: 0.25rem;">
          <span :class="['status-dot', value === 'ACTIVE' || value === undefined ? 'active' : 'inactive']"></span>
          <span :class="['status-text-label', value === 'ACTIVE' || value === undefined ? 'text-active' : 'text-inactive']">
            {{ value === 'ACTIVE' || value === undefined ? '上架中' : '已下架' }}
          </span>
        </div>
      </template>
      <template #actions="{ row }">
        <div class="action-buttons-wrap">
          <button class="btn btn-edit edit-btn-style btn-sm" @click="openEditPrivateCourseModal(row)">編輯</button>
          <button v-if="row.status === 'ACTIVE' || row.status === undefined" class="btn btn-danger deactive-btn-style btn-sm" @click="deactivatePrivateCourse(row.id || row.privateCourseId)">下架</button>
          <button v-if="row.status === 'INACTIVE'" class="btn btn-edit edit-btn-style btn-sm" @click="activatePrivateCourse(row.id || row.privateCourseId)">上架</button>
        </div>
      </template>
    </AdminTable>

    <!-- 編輯私教課程 Modal -->
    <PrivateCourseModal
      v-model:visible="showEditPrivateCourse"
      :course="editPrivateCourseForm"
      :trainersList="trainersList"
      :categories="privateCourseCategories"
      :saving="saving"
      @submit="updatePrivateCourse"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, inject } from 'vue'
import AdminFormCard from '@/components/AdminFormCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AdminTable from '@/components/AdminTable.vue'
import PrivateCourseModal from '@/components/admin/PrivateCourseModal.vue'
import { formatImageUrl, useFallbackImage, DEFAULT_COURSE_IMAGE } from '@/utils/image.js'
import api from '@/api/axios.instance.js'

const props = defineProps({
  categories: { type: Array, default: () => [] },
  trainersList: { type: Array, default: () => [] }
})

const showToast = inject('showToast', null)

const saving = ref(false)
const showAddPrivateCourse = ref(false)
const showEditPrivateCourse = ref(false)
const uploadingFile = ref(false)

const privateCourseForm = ref({
  name: '',
  trainerId: null,
  maxCapacity: 1,
  sessionPerUnit: 1,
  price: 0,
  description: '',
  imageUrl: '',
  isTrial: false,
  courseCategoryId: null
})

const editPrivateCourseForm = ref({
  privateCourseId: null,
  name: '',
  trainerId: null,
  maxCapacity: 1,
  sessionPerUnit: 1,
  price: 0,
  description: '',
  imageUrl: '',
  isTrial: false,
  courseCategoryId: null
})

const rawPrivateCourses = ref([])
const pcCurrentPage = ref(1)
const pcItemsPerPage = ref(10)

const pcFilterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})
const pcActiveFilter = ref('ALL')

const pcCategoryFilters = [
  { value: 'ALL',      label: '全部課程' },
  { value: 'ACTIVE',   label: '上架中' },
  { value: 'INACTIVE', label: '已下架' }
]

const pcColumns = [
  { key: 'id', label: 'ID', width: '80px' },
  { key: 'imageUrl', label: '圖片', width: '100px' },
  { key: 'name', label: '課程名稱 & 說明', width: '300px', align: 'left' },
  { key: 'trainerName', label: '指導教練', width: '150px' },
  { key: 'sessionPerUnit', label: '堂數 & 人數', width: '120px' },
  { key: 'price', label: '定價金額', width: '120px' },
  { key: 'status', label: '狀態', width: '100px' },
  { key: 'actions', label: '操作', width: '150px' }
]

const privateCourseCategories = computed(() => props.categories.filter(cat => cat.name === '重量訓練'))

async function loadPrivateCourses() {
  try {
    const res = await api.get('/api/admin/private-courses/paged', {
      params: { page: 0, size: 1000 }
    })
    rawPrivateCourses.value = res.data?.items || []
  } catch (err) {
    console.error('Failed to load private courses:', err)
  }
}

async function createPrivateCourse() {
  saving.value = true
  try {
    await api.post('/api/admin/private-courses', {
      name: privateCourseForm.value.name,
      trainerId: privateCourseForm.value.trainerId,
      maxCapacity: privateCourseForm.value.maxCapacity,
      sessionPerUnit: privateCourseForm.value.sessionPerUnit,
      price: privateCourseForm.value.price,
      description: privateCourseForm.value.description,
      imageUrl: privateCourseForm.value.imageUrl || null,
      isTrial: privateCourseForm.value.isTrial,
      courseCategoryId: privateCourseForm.value.courseCategoryId
    })
    if (showToast) showToast('私教課程新增成功')
    showAddPrivateCourse.value = false
    privateCourseForm.value = {
      name: '',
      trainerId: null,
      maxCapacity: 1,
      sessionPerUnit: 1,
      price: 0,
      description: '',
      imageUrl: '',
      isTrial: false,
      courseCategoryId: null
    }
    await loadPrivateCourses()
  } catch (e) {
    if (showToast) showToast('新增失敗', 'error')
  } finally {
    saving.value = false
  }
}

async function deactivatePrivateCourse(id) {
  if (!confirm('確認下架此私教課程？下架後會員將無法預約此課程。')) return
  try {
    await api.post(`/api/admin/private-courses/${id}/deactivate`)
    if (showToast) showToast('私教課程已下架')
    await loadPrivateCourses()
  } catch (e) {
    if (showToast) showToast('下架失敗', 'error')
  }
}

async function activatePrivateCourse(id) {
  try {
    await api.post(`/api/admin/private-courses/${id}/activate`)
    if (showToast) showToast('私教課程已重新上架')
    await loadPrivateCourses()
  } catch (e) {
    if (showToast) showToast('上架失敗', 'error')
  }
}

function openEditPrivateCourseModal(c) {
  editPrivateCourseForm.value = {
    privateCourseId: c.id || c.privateCourseId,
    name: c.name,
    maxCapacity: c.maxCapacity || 1,
    sessionPerUnit: c.sessionPerUnit || c.sessionsPerUnit || 1,
    price: c.price || 0,
    description: c.description || '',
    imageUrl: c.imageUrl || '',
    isTrial: c.isTrial === true,
    courseCategoryId: c.courseCategoryId ? Number(c.courseCategoryId) : null,
    trainerId: c.trainerId ? Number(c.trainerId) : (c.trainer?.id ? Number(c.trainer.id) : null)
  }
  showEditPrivateCourse.value = true
}

async function updatePrivateCourse(updatedCourse) {
  saving.value = true
  try {
    await api.put(`/api/admin/private-courses/${updatedCourse.privateCourseId}`, {
      name: updatedCourse.name,
      description: updatedCourse.description,
      imageUrl: updatedCourse.imageUrl || null,
      isTrial: updatedCourse.isTrial,
      maxCapacity: updatedCourse.maxCapacity,
      sessionPerUnit: updatedCourse.sessionPerUnit,
      price: updatedCourse.price,
      courseCategoryId: updatedCourse.courseCategoryId,
      trainerId: updatedCourse.trainerId
    })
    if (showToast) showToast('私教課程資料修改成功！')
    showEditPrivateCourse.value = false
    await loadPrivateCourses()
  } catch (e) {
    if (showToast) showToast(e.message || '修改失敗', 'error')
  } finally {
    saving.value = false
  }
}

async function uploadFile(event) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    if (showToast) showToast('照片檔案不能超過 5MB', 'error')
    return
  }
  uploadingFile.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/api/admin/products/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const url = res.url || res.data?.url
    if (url) {
      privateCourseForm.value.imageUrl = url
    }
  } catch (e) {
    console.error(e)
  } finally {
    uploadingFile.value = false
  }
}

const filteredPrivateCourses = computed(() => {
  let list = rawPrivateCourses.value

  if (pcActiveFilter.value !== 'ALL') {
    list = list.filter(c => {
      const isActive = c.status === 'ACTIVE' || c.status === undefined
      return pcActiveFilter.value === 'ACTIVE' ? isActive : !isActive
    })
  }

  if (pcFilterParams.value.keyword.trim()) {
    const kw = pcFilterParams.value.keyword.trim().toLowerCase()
    list = list.filter(c =>
      c.name?.toLowerCase().includes(kw) ||
      c.trainerName?.toLowerCase().includes(kw) ||
      c.trainer?.name?.toLowerCase().includes(kw)
    )
  }

  if (pcFilterParams.value.startDate) {
    const sDate = new Date(pcFilterParams.value.startDate + 'T00:00:00').getTime()
    list = list.filter(c => c.createdAt && new Date(c.createdAt).getTime() >= sDate)
  }
  if (pcFilterParams.value.endDate) {
    const eDate = new Date(pcFilterParams.value.endDate + 'T23:59:59').getTime()
    list = list.filter(c => c.createdAt && new Date(c.createdAt).getTime() <= eDate)
  }

  return list
})

const pcTotalItems = computed(() => filteredPrivateCourses.value.length)
const pcTotalPages = computed(() => Math.ceil(pcTotalItems.value / pcItemsPerPage.value) || 1)

const paginatedPrivateCourses = computed(() => {
  const start = (pcCurrentPage.value - 1) * pcItemsPerPage.value
  const end = start + pcItemsPerPage.value
  return filteredPrivateCourses.value.slice(start, end)
})

watch([pcActiveFilter, pcItemsPerPage], () => {
  pcCurrentPage.value = 1
})

function onPcSearch() {
  pcCurrentPage.value = 1
}

function resetPcFilters() {
  pcFilterParams.value = { keyword: '', startDate: '', endDate: '' }
  pcActiveFilter.value = 'ALL'
  pcCurrentPage.value = 1
}

onMounted(() => {
  loadPrivateCourses()
})
</script>
