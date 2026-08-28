<template>
  <div>
    <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
      <button class="btn btn-primary btn-sm" @click="showAddGroupCourse = !showAddGroupCourse">
        <span>{{ showAddGroupCourse ? '✕ 取消新增' : '＋ 新增團體課程' }}</span>
      </button>
      <button class="btn btn-outline btn-sm" @click="$emit('openCategoryManager')">
        <span>⚙ 管理課程分類</span>
      </button>
    </div>

    <!-- 新增團體課程表單 -->
    <Transition name="slide">
      <AdminFormCard
        v-if="showAddGroupCourse"
        title="新增團體課程資料"
        helper="*請確實填寫指導教練與課程資訊"
        @submit="createGroupCourse"
      >
        <div class="form-group" style="grid-column: span 2;">
          <label class="form-label">課程名稱</label>
          <input v-model="groupCourseForm.name" class="form-input" placeholder="例如: 燃脂有氧、基礎空中瑜珈" required />
        </div>
        <div class="form-group">
          <label class="form-label">指導教練</label>
          <select v-model="groupCourseForm.trainerId" class="form-input" required>
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
            <select v-model="groupCourseForm.categoryId" class="form-input" required style="flex:1; max-width: 240px;">
              <option :value="null" disabled>請選擇分類</option>
              <option v-for="cat in categories" :key="cat.id" :value="cat.id">
                {{ cat.name }}
              </option>
            </select>
            <label class="checkbox-container" style="white-space:nowrap; flex-shrink:0;">
              <input v-model="groupCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
              <span class="checkmark"></span>
              <span class="checkbox-label-text">免費體驗</span>
            </label>
          </div>
        </div>
        <div class="form-group">
          <label class="form-label">上限人數</label>
          <input v-model.number="groupCourseForm.maxCapacity" type="number" class="form-input" min="1" max="100" required />
        </div>

        <template #extra>
          <div class="form-group file-upload-group" style="margin-top: 1rem; width: 100%;">
            <label class="form-label">課程照片</label>
            <div class="file-upload-wrapper" style="display: flex; align-items: center; gap: 1rem; margin-bottom: 0.5rem;">
              <label class="custom-file-btn" style="background: var(--primary, #5a4a42); color: #ffffff; padding: 0.5rem 1rem; border-radius: 6px; cursor: pointer; display: inline-block;">
                選擇照片
                <input type="file" @change="e => uploadFile(e, 'addGroupCourse')" accept="image/*" style="display: none;" />
              </label>
              <span v-if="uploadingFile" class="loading-spinner-inline"></span>
              <span class="file-status-text" v-if="groupCourseForm.imageUrl" style="font-size: 0.8rem; color: #10b981;">已上傳圖片</span>
              <img v-if="groupCourseForm.imageUrl" :src="formatImageUrl(groupCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" style="width: 90px; height: 90px; object-fit: cover; border-radius: 6px; border: 1px solid var(--border, #e2e8f0);" />
            </div>
            <input v-model="groupCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" style="width: 100%; margin-top: 0.5rem;" />
          </div>
          <div class="form-group" style="margin-top: 1rem; width: 100%;">
            <label class="form-label">詳細說明介紹</label>
            <textarea v-model="groupCourseForm.description" class="form-input" rows="4" placeholder="請填寫課程特色、難易度、注意事項..." style="width: 100%; padding: 0.5rem; box-sizing: border-box;"></textarea>
          </div>
        </template>

        <template #actions>
          <button type="submit" class="btn btn-primary" :disabled="saving">確認新增上傳</button>
        </template>
      </AdminFormCard>
    </Transition>

    <!-- 統一搜尋與篩選列 -->
    <SearchFilter
      v-model="gcFilterParams"
      keyword-label="團體課程搜尋"
      keyword-placeholder="搜尋課程名稱、指導教練姓名..."
      date-label="上架日期範圍"
      @search="onGcSearch"
      @reset="resetGcFilters"
    />

    <!-- 快速狀態篩選 -->
    <CategoryFilter
      v-model="gcActiveFilter"
      label="快速狀態篩選："
      :options="gcCategoryFilters"
    />

    <!-- 團體課程列表 -->
    <AdminTable
      id="gcTable"
      :columns="gcColumns"
      :data="rawGroupCourses"
      rowKey="id"
      :totalItems="gcTotalItems"
      :totalPages="gcTotalPages"
      v-model:currentPage="gcCurrentPage"
      v-model:itemsPerPage="gcItemsPerPage"
    >
      <template #id="{ value }">
        #{{ value }}
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
        <div style="font-weight: 600; color: var(--primary-dark);">{{ row.trainerName || '未分配' }}</div>
      </template>
      <template #maxCapacity="{ row }">
        <span class="badge badge-primary" style="display:block; margin-bottom:4px; max-width: fit-content; margin: 0 auto 4px auto;">{{ row.courseCategoryName || '未分類' }}</span>
        <span style="font-size:0.82rem; color: var(--text-muted);">上限 {{ row.maxCapacity }} 人</span>
      </template>
      <template #isTrial="{ value }">
        <span :class="['badge', value ? 'badge-success' : 'badge-danger']">
          {{ value ? '開放體驗' : '無體驗' }}
        </span>
      </template>
      <template #status="{ value }">
        <div class="status-cell-wrap" style="display: flex; align-items: center; justify-content: center; gap: 0.25rem;">
          <span :class="['status-dot', value === 'ACTIVE' ? 'active' : 'inactive']"></span>
          <span :class="['status-text-label', value === 'ACTIVE' ? 'text-active' : 'text-inactive']">
            {{ value === 'ACTIVE' ? '上架中' : '已下架' }}
          </span>
        </div>
      </template>
      <template #actions="{ row }">
        <div class="action-buttons-wrap">
          <button class="btn btn-edit edit-btn-style btn-sm" @click="openEditGroupCourseModal(row)">編輯</button>
          <button v-if="row.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style btn-sm" @click="deleteGroupCourse(row.id)">下架</button>
          <button v-if="row.status === 'INACTIVE'" class="btn btn-edit edit-btn-style btn-sm" @click="activateGroupCourse(row.id)">上架</button>
        </div>
      </template>
    </AdminTable>

    <!-- 編輯團體課程 Modal -->
    <GroupCourseModal
      v-model:visible="showEditGroupCourse"
      :course="editGroupCourseForm"
      :trainersList="trainersList"
      :categories="categories"
      :saving="saving"
      @submit="updateGroupCourse"
    />
  </div>
</template>

<script setup>
import { ref, watch, onMounted, inject } from 'vue'
import AdminFormCard from '@/components/AdminFormCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AdminTable from '@/components/AdminTable.vue'
import GroupCourseModal from '@/components/admin/GroupCourseModal.vue'
import { formatImageUrl, useFallbackImage, DEFAULT_COURSE_IMAGE } from '@/utils/image.js'
import api from '@/api/axios.instance.js'

defineProps({
  categories: { type: Array, default: () => [] },
  trainersList: { type: Array, default: () => [] }
})

defineEmits(['openCategoryManager'])
const showToast = inject('showToast', null)

const saving = ref(false)
const showAddGroupCourse = ref(false)
const showEditGroupCourse = ref(false)
const uploadingFile = ref(false)

const groupCourseForm = ref({
  name: '',
  trainerId: null,
  maxCapacity: 20,
  categoryId: null,
  description: '',
  imageUrl: '',
  isTrial: false
})

const editGroupCourseForm = ref({
  groupCourseId: null,
  name: '',
  trainerId: null,
  maxCapacity: 20,
  categoryId: null,
  description: '',
  imageUrl: '',
  isTrial: false,
  status: 'ACTIVE'
})

const rawGroupCourses = ref([])
const gcCurrentPage = ref(1)
const gcItemsPerPage = ref(10)
const gcTotalItems = ref(0)
const gcTotalPages = ref(1)

const gcFilterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})
const gcActiveFilter = ref('ALL')

const gcCategoryFilters = [
  { value: 'ALL',      label: '全部課程' },
  { value: 'ACTIVE',   label: '上架中' },
  { value: 'INACTIVE', label: '已下架' }
]

const gcColumns = [
  { key: 'id', label: 'ID', width: '80px' },
  { key: 'imageUrl', label: '圖片', width: '100px' },
  { key: 'name', label: '課程名稱 & 說明', width: '300px', align: 'left' },
  { key: 'trainerName', label: '指導教練', width: '150px' },
  { key: 'maxCapacity', label: '上限人數', width: '120px' },
  { key: 'isTrial', label: '類型', width: '100px' },
  { key: 'status', label: '狀態', width: '100px' },
  { key: 'actions', label: '操作', width: '150px' }
]

function onGcSearch() {
  gcCurrentPage.value = 1
  loadGroupCourses()
}

function resetGcFilters() {
  gcFilterParams.value = { keyword: '', startDate: '', endDate: '' }
  gcActiveFilter.value = 'ALL'
  gcCurrentPage.value = 1
  loadGroupCourses()
}

async function loadGroupCourses() {
  try {
    const params = {
      page: gcCurrentPage.value - 1,
      size: gcItemsPerPage.value
    }
    if (gcFilterParams.value.keyword.trim()) {
      params.search = gcFilterParams.value.keyword.trim()
    }
    if (gcActiveFilter.value !== 'ALL') {
      params.status = gcActiveFilter.value
    }
    if (gcFilterParams.value.startDate) {
      params.startDate = gcFilterParams.value.startDate
    }
    if (gcFilterParams.value.endDate) {
      params.endDate = gcFilterParams.value.endDate
    }
    const res = await api.get('/api/admin/group-courses', { params })
    const data = res.data
    rawGroupCourses.value = data.items || []
    gcTotalItems.value = data.totalItems ?? rawGroupCourses.value.length
    gcTotalPages.value = data.totalPages ?? 1
  } catch (err) {
    console.error('Failed to load group courses:', err)
  }
}

async function createGroupCourse() {
  if (!groupCourseForm.value.name || !groupCourseForm.value.trainerId || !groupCourseForm.value.categoryId) {
    if (showToast) showToast('請填寫完整必填欄位', 'warning')
    return
  }
  saving.value = true
  try {
    await api.post('/api/admin/group-courses', {
      name: groupCourseForm.value.name,
      trainerId: groupCourseForm.value.trainerId,
      maxCapacity: groupCourseForm.value.maxCapacity,
      courseCategoryId: groupCourseForm.value.categoryId,
      description: groupCourseForm.value.description,
      imageUrl: groupCourseForm.value.imageUrl || null,
      isTrial: groupCourseForm.value.isTrial
    })
    if (showToast) showToast('團體課程新增成功！')
    showAddGroupCourse.value = false
    groupCourseForm.value = {
      name: '',
      trainerId: null,
      maxCapacity: 20,
      categoryId: null,
      description: '',
      imageUrl: '',
      isTrial: false
    }
    await loadGroupCourses()
  } catch (e) {
    if (showToast) showToast(e.message || '新增失敗', 'error')
  } finally {
    saving.value = false
  }
}

async function deleteGroupCourse(id) {
  if (!confirm('確認將此團體課程下架？下架後學員將無法排班此課程。')) return
  try {
    await api.delete(`/api/admin/group-courses/${id}`)
    if (showToast) showToast('課程下架成功')
    await loadGroupCourses()
  } catch (e) {
    if (showToast) showToast('下架失敗', 'error')
  }
}

async function activateGroupCourse(id) {
  try {
    await api.post(`/api/admin/group-courses/${id}/activate`)
    if (showToast) showToast('課程重新上架成功')
    await loadGroupCourses()
  } catch (e) {
    if (showToast) showToast('上架失敗', 'error')
  }
}

function openEditGroupCourseModal(c) {
  editGroupCourseForm.value = {
    groupCourseId: c.id,
    name: c.name,
    trainerId: c.trainerId,
    maxCapacity: c.maxCapacity,
    categoryId: c.courseCategoryId,
    description: c.description || '',
    imageUrl: c.imageUrl || '',
    isTrial: c.isTrial === true,
    status: c.status
  }
  showEditGroupCourse.value = true
}

async function updateGroupCourse(updatedCourse) {
  saving.value = true
  try {
    await api.put(`/api/admin/group-courses/${updatedCourse.groupCourseId}`, {
      name: updatedCourse.name,
      description: updatedCourse.description,
      imageUrl: updatedCourse.imageUrl || null,
      isTrial: updatedCourse.isTrial,
      maxCapacity: updatedCourse.maxCapacity,
      courseCategoryId: updatedCourse.categoryId,
      status: updatedCourse.status,
      trainerId: updatedCourse.trainerId
    })
    if (showToast) showToast('團體課程資料修改成功！')
    showEditGroupCourse.value = false
    await loadGroupCourses()
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
      groupCourseForm.value.imageUrl = url
    }
  } catch (e) {
    console.error(e)
  } finally {
    uploadingFile.value = false
  }
}

watch([gcActiveFilter, gcCurrentPage, gcItemsPerPage], () => {
  loadGroupCourses()
})

let gcSearchTimer = null
watch(() => gcFilterParams.value.keyword, () => {
  clearTimeout(gcSearchTimer)
  gcSearchTimer = setTimeout(() => {
    gcCurrentPage.value = 1
    loadGroupCourses()
  }, 400)
})

watch([() => gcFilterParams.value.startDate, () => gcFilterParams.value.endDate], () => {
  gcCurrentPage.value = 1
  loadGroupCourses()
})

onMounted(() => {
  loadGroupCourses()
})
</script>
